#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""提取IC-705 CI-V协议PDF完整内容"""

import PyPDF2
import sys

pdf_path = r'd:\BH6AAP\aapsctror\ic705controler\IC-705_ENG_CI-V_6.pdf'

try:
    print(f"Opening: {pdf_path}")
    with open(pdf_path, 'rb') as f:
        reader = PyPDF2.PdfReader(f)
        print(f"Total pages: {len(reader.pages)}")
        
        full_text = ""
        for i, page in enumerate(reader.pages):
            text = page.extract_text()
            if text:
                full_text += f"\n=== Page {i+1} ===\n"
                full_text += text + "\n"
        
        output_file = r'd:\BH6AAP\aapsctror\ic705controler\civ_protocol_full.txt'
        with open(output_file, 'w', encoding='utf-8') as out:
            out.write(full_text)
        
        print(f"Full text saved to: {output_file}")
        
except Exception as e:
    print(f"Error: {e}")
    import traceback
    traceback.print_exc()
