import spacy
import sys
import json
import os
import logging
import re
from difflib import get_close_matches
from datetime import datetime

# --- 1. CONFIGURAÇÃO ---
# Direciona todos os logs para stderr, deixando stdout limpo para o JSON de saída.
logging.basicConfig(level=logging.INFO, format='PLN_PY: %(levelname)s - %(message)s', stream=sys.stderr)

try:
    SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
except NameError:
    # Fallback para ambientes interativos onde __file__ não está definido.
    SCRIPT_DIR = os.getcwd()

logging.info(f"Diretório base do script (SCRIPT_DIR) definido como: {SCRIPT_DIR}")

# --- 2. FUNÇÕES AUXILIARES E DE CARREGAMENTO ---

def exit_with_json_error(message):
    """Encerra o script e imprime uma mensagem de erro JSON para stdout."""
    logging.error(message)
    print(json.dumps({"erro": message}, ensure_ascii=False))
    sys.exit(0) # Saímos com sucesso (0) pois o erro foi lógico, não de execução.

def load_resource(filename, loader_func):
    """Carrega um arquivo de recurso de forma segura."""
    path = os.path.join(SCRIPT_DIR, filename)
    if not os.path.exists(path):
        exit_with_json_error(f"Arquivo de recurso essencial não encontrado: {path}")
    try:
        with open(path, 'r', encoding='utf-8') as f:
            return loader_func(f)
    except Exception as e:
        exit_with_json_error(f"Erro ao carregar ou parsear o arquivo '{path}': {e}")

def normalizar_texto(texto: str):
    """Função de normalização padrão para chaves de mapa e texto da pergunta."""
    if not isinstance(texto, str): return ""
    return texto.lower().strip()

# --- 3. CARREGAMENTO DE MODELOS E DADOS ---

try:
    nlp = spacy.load("pt_core_news_sm")
    logging.info("Modelo spaCy 'pt_core_news_sm' carregado com sucesso.")
except OSError:
    exit_with_json_error("Modelo spaCy 'pt_core_news_sm' não encontrado. Verifique se foi baixado no ambiente.")

# Carrega mapas de dados com chaves normalizadas
empresa_nome_map = {normalizar_texto(k): v for k, v in load_resource("empresa_nome_map.json", json.load).items()}
setor_map_global = {normalizar_texto(k): v for k, v in load_resource("setor_map.json", json.load).items()}

def _load_sinonimos(f):
    sinonimos = {}
    for line in f:
        if line.strip() and not line.startswith('#'):
            parts = [p.strip() for p in line.split(';')]
            if len(parts) == 2:
                sinonimos[normalizar_texto(parts[0])] = parts[1]
    return sinonimos
sinonimos_map = load_resource("resultado_similaridade.txt", _load_sinonimos)

# --- 4. LÓGICA DE PROCESSAMENTO DE LINGUAGEM NATURAL ---

def selecionar_template(pergunta: str) -> str | None:
    """Compara a pergunta do usuário com uma lista de perguntas padrão para encontrar a melhor intenção/template."""
    pergunta_norm = normalizar_texto(pergunta)
    path_perguntas = os.path.join(SCRIPT_DIR, "perguntas_de_interesse.txt")
    
    perguntas_base = {}
    with open(path_perguntas, 'r', encoding='utf-8') as f:
        for line in f:
            if line.strip() and not line.startswith('#'):
                parts = line.strip().split(';', 1)
                if len(parts) == 2:
                    # Remove placeholders como <data> para uma comparação mais precisa
                    pergunta_sem_ph = re.sub(r'<[^>]+>', '', parts[1])
                    perguntas_base[normalizar_texto(pergunta_sem_ph)] = parts[0].strip()
    
    melhores_matches = get_close_matches(pergunta_norm, perguntas_base.keys(), n=1, cutoff=0.6)
    if melhores_matches:
        template_nome = perguntas_base[melhores_matches[0]]
        logging.info(f"Template selecionado: '{template_nome}' para a pergunta: '{pergunta}'")
        return template_nome.replace(" ", "_") # Garante o formato com underscore
    
    logging.warning(f"Nenhum template encontrado para a pergunta: '{pergunta}'")
    return None

def mapear_entidades(pergunta: str, template_id: str) -> dict:
    """Extrai entidades da pergunta e as formata para os placeholders da query SPARQL."""
    placeholders = {}
    texto_norm = normalizar_texto(pergunta)
    doc = nlp(pergunta)

    # 1. Mapear Data
    match_data = re.search(r'\b(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})\b', pergunta)
    if match_data:
        date_str = match_data.group(1)
        for fmt in ('%d/%m/%Y', '%d-%m-%Y', '%d/%m/%y', '%d-%m-%y'):
            try:
                data_iso = datetime.strptime(date_str, fmt).strftime('%Y-%m-%d')
                # Formata diretamente para a sintaxe SPARQL
                placeholders["#DATA#"] = f'"{data_iso}"^^xsd:date'
                logging.info(f"Entidade #DATA# mapeada para: {placeholders['#DATA#']}")
                break
            except ValueError:
                continue

    # 2. Mapear Valor Desejado (Métrica de Preço)
    for sinonimo, prop_ontologia in sorted(sinonimos_map.items(), key=lambda item: len(item[0]), reverse=True):
        if sinonimo in texto_norm:
            placeholders["#VALOR_DESEJADO#"] = f'b3:{prop_ontologia}'
            logging.info(f"Entidade #VALOR_DESEJADO# mapeada para: {placeholders['#VALOR_DESEJADO#']}")
            break

    # 3. Mapear Entidade (Empresa ou Ticker)
    entidade_encontrada = None
    # Prioriza a busca por um ticker exato no texto
    ticker_match = re.search(r'\b([A-Z]{4}\d{1,2})\b', pergunta.upper())
    if ticker_match:
        entidade_encontrada = ticker_match.group(1)
        logging.info(f"Entidade encontrada por Regex de Ticker: '{entidade_encontrada}'")
    else:
        # Se não for ticker, busca por nome no mapa
        for nome_mapa, nome_canônico in sorted(empresa_nome_map.items(), key=lambda item: len(item[0]), reverse=True):
            if nome_mapa in texto_norm:
                entidade_encontrada = nome_canônico
                logging.info(f"Entidade encontrada por Nome do Mapa: '{entidade_encontrada}' (chave: '{nome_mapa}')")
                break
    
    if entidade_encontrada:
        # Escapa aspas no valor para evitar quebrar o JSON/SPARQL
        valor_escapado = entidade_encontrada.replace('"', '\\"')
        placeholders["#ENTIDADE_NOME#"] = f'"{valor_escapado}"'
        logging.info(f"Entidade #ENTIDADE_NOME# mapeada para: {placeholders['#ENTIDADE_NOME#']}")
    
    # 4. Mapear Setor
    if template_id == "Template_3A":
        for nome_mapa, nome_canônico in sorted(setor_map_global.items(), key=lambda item: len(item[0]), reverse=True):
            if nome_mapa in texto_norm:
                placeholders["#SETOR#"] = f'"{nome_canônico}"'
                logging.info(f"Entidade #SETOR# mapeada para: {placeholders['#SETOR#']}")
                break

    return placeholders

# --- 5. FUNÇÃO PRINCIPAL DE EXECUÇÃO ---

def main(pergunta_usuario: str):
    """Orquestra o processo de PLN e imprime o resultado JSON."""
    logging.info(f"Processando a pergunta recebida do Java: '{pergunta_usuario}'")
    
    template_id = selecionar_template(pergunta_usuario)
    if not template_id:
        exit_with_json_error("Não foi possível determinar a intenção da pergunta (nenhum template compatível encontrado).")
        return

    placeholders = mapear_entidades(pergunta_usuario, template_id)
    
    # Validação final dos placeholders necessários
    ph_essenciais = {
        "Template_1A": ["#ENTIDADE_NOME#", "#DATA#", "#VALOR_DESEJADO#"],
        "Template_1B": ["#ENTIDADE_NOME#", "#DATA#", "#VALOR_DESEJADO#"],
        "Template_2A": ["#ENTIDADE_NOME#"],
        "Template_3A": ["#SETOR#"]
    }

    if template_id in ph_essenciais:
        faltando = [ph for ph in ph_essenciais[template_id] if ph not in placeholders]
        if faltando:
            exit_with_json_error(f"Não foi possível extrair todas as informações necessárias. Faltando: {', '.join(faltando)}")
            return

    # Se tudo correu bem, monta o objeto de resposta final
    resposta_final = {
        "template_nome": template_id,
        "mapeamentos": placeholders
    }
    
    # Imprime o JSON para stdout, que será capturado pelo processo Java
    print(json.dumps(resposta_final, ensure_ascii=False))
    logging.info("Processamento PLN concluído com sucesso. JSON enviado para stdout.")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        exit_with_json_error("Nenhum argumento de pergunta foi fornecido ao script Python.")
    
    # Junta todos os argumentos caso a pergunta contenha espaços
    pergunta_completa = " ".join(sys.argv[1:])
    main(pergunta_completa)