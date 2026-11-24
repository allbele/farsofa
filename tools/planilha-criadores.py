#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Turns marketing/criadores/criadores.csv into the workbook used to send the store keys to creators.

Usage: /usr/bin/python3 tools/planilha-criadores.py   (needs openpyxl)
"""
import csv
from pathlib import Path

from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter
from openpyxl.worksheet.datavalidation import DataValidation

FOLDER = Path(__file__).resolve().parent.parent / "marketing" / "criadores"
rows = list(csv.DictReader(open(FOLDER / "criadores.csv", encoding="utf-8")))
order = {"A": 0, "B": 1, "C": 2}
rows.sort(key=lambda row: (order.get(row["priority"], 3), row["language"], -int(row["subscribers"] or 0)))

LANGUAGE = {"en": "Inglês", "pt": "Português", "es": "Espanhol"}
HEAD = ["Prioridade", "Nome", "Idioma", "Inscritos", "E-mail", "Outro contato", "Canal", "Foco", "Por que serve",
        "Vídeo de referência", "Origem", "Rascunho no Wonderpieces", "Status", "Data do envio", "Chave enviada", "Resposta / resultado"]
WIDTH = [11, 30, 11, 11, 36, 34, 42, 34, 60, 46, 16, 44, 16, 14, 26, 40]
STATUS = ["A contatar", "Enviado", "Respondeu", "Chave enviada", "Fez vídeo", "Sem resposta", "Não quis"]


def line(row):
    contact = row["contact"].strip()
    email = contact if "@" in contact and " " not in contact else ""
    return [row["priority"], row["name"], LANGUAGE.get(row["language"], row["language"]),
            int(row["subscribers"]) if row["subscribers"].strip().isdigit() else None,
            email, "" if email else contact, row["url"], row["focus"], row["why_fit"], row["evidence_url"],
            "Wonderpieces" if row["source"].startswith("reused") else "Nova pesquisa", row["prior_contact"], "A contatar", None, None, None]


def sheet(book, title, selected):
    page = book.create_sheet(title)
    page.append(HEAD)
    for cell in page[1]:
        cell.font = Font(bold=True, color="FFFFFF")
        cell.fill = PatternFill("solid", fgColor="2B3A67")
        cell.alignment = Alignment(vertical="center", wrap_text=True)
    for row in selected:
        page.append(line(row))
    for index, width in enumerate(WIDTH, start=1):
        page.column_dimensions[get_column_letter(index)].width = width
    for cells in page.iter_rows(min_row=2):
        for cell in cells:
            cell.alignment = Alignment(vertical="top", wrap_text=cell.column in (8, 9, 12, 16))
        for column in (5, 7, 10):
            cell = cells[column - 1]
            if cell.value and (str(cell.value).startswith("http") or "@" in str(cell.value)):
                cell.hyperlink = cell.value if str(cell.value).startswith("http") else "mailto:" + cell.value
                cell.font = Font(color="1A56DB", underline="single")
    if selected:
        choice = DataValidation(type="list", formula1='"' + ",".join(STATUS) + '"', allow_blank=True)
        page.add_data_validation(choice)
        choice.add(f"M2:M{len(selected) + 1}")
        page.auto_filter.ref = f"A1:{get_column_letter(len(HEAD))}{len(selected) + 1}"
    page.freeze_panes = "C2"
    return page


book = Workbook()
book.remove(book.active)
creators = [row for row in rows if row["platform"] == "YouTube"]
with_email = [row for row in creators if "@" in row["contact"] and " " not in row["contact"].strip()]
sheet(book, "Enviar primeiro (com e-mail)", with_email)
sheet(book, "Sem e-mail público", [row for row in creators if row not in with_email])
sheet(book, "Imprensa e comunidades", [row for row in rows if row["platform"] != "YouTube"])
sheet(book, "Todos", rows)

notes = book.create_sheet("Como usar", 0)
for text in [
    "Farsofa — contatos para divulgação",
    "",
    "Montada em 24/11/2026 a partir de marketing/criadores/criadores.csv. Ninguém desta lista foi contatado ainda.",
    "Só enviar depois de o app estar aprovado na loja da Meta: as chaves grátis só existem a partir daí.",
    "",
    "Ordem: aba 'Enviar primeiro', de cima para baixo (prioridade A, depois B, depois C).",
    "Uma mensagem por pessoa, citando o vídeo da coluna 'Vídeo de referência'. Nada de envio em massa.",
    "Canais com rascunho no Wonderpieces: não mandar as duas abordagens na mesma semana.",
    "Inscritos dos canais vindos do Wonderpieces são de 20/10/2026. E-mails são os publicados pelos próprios canais; confira antes de enviar.",
    "Atualize as colunas Status, Data do envio, Chave enviada e Resposta a cada passo. Guarde cada chave gerada: a Meta não mostra de novo.",
    "",
    f"Totais: {len(creators)} canais ({len(with_email)} com e-mail público), {len(rows) - len(creators)} veículos e comunidades.",
]:
    notes.append([text])
notes["A1"].font = Font(bold=True, size=14)
notes.column_dimensions["A"].width = 130

out = FOLDER / "CONTATOS-FARSOFA.xlsx"
book.save(out)
print(out, len(creators), len(with_email), len(rows) - len(creators))
