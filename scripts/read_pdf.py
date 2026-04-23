#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""读取IC-705 CIV协议PDF"""

import PyPDF2
import sys

pdf_path = r'd:\BH6AAP\aapsctror\ic705controler\IC-705_ENG_CI-V_6.pdf'

try:
    with open(pdf_path, 'rb') as f:
        reader = PyPDF2.PdfReader(f)
        print(f"Total pages: {len(reader.pages)}")
        
        # 读取所有页面并搜索PTT相关命令
        full_text = ""
        for i, page in enumerate(reader.pages):
            text = page.extract_text()
            if text:
                full_text += text + "\n"
        
        # 搜索关键词
        keywords = ['PTT', '0x1C', '1C', 'transmit', 'key', 'command', '0x00', '0x03', '0x25', '0x26']
        lines = full_text.split('\n')
        
        for i, line in enumerate(lines):
            for keyword in keywords:
                if keyword.upper() in line.upper():
                    # 打印上下文
                    start = max(0, i-2)
                    end = min(len(lines), i+3)
                    print(f"\n=== Line {i+1} (keyword: {keyword}) ===")
                    for j in range(start, end):
                        marker = ">>> " if j == i else "    "
                        print(f"{marker}{lines[j]}")
                    break
        
        # 保存完整文本
        with open('civ_protocol.txt', 'w', encoding='utf-8') as out:
            out.write(full_text)
        print("\n\nFull text saved to civ_protocol.txt")
        
except Exception as e:
    print(f"Error: {e}")
    import traceback
    traceback.print_exc()
