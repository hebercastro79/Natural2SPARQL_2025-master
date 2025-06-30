#!/usr/bin/env bash
set -o errexit
pip install -r requirements.txt
python -m spacy download pt_core_news_sm