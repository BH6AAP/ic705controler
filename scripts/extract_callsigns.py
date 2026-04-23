#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
提取CSV文件中CALL列的呼号，去重并保存到txt文件
"""

import csv
import os

def extract_callsigns(csv_file, output_file):
    """从CSV文件中提取CALL列的呼号，去重并排序"""
    callsigns = set()
    
    with open(csv_file, 'r', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for row in reader:
            callsign = row.get('CALL', '').strip()
            if callsign:
                callsigns.add(callsign.upper())
    
    # 排序并写入文件
    sorted_callsigns = sorted(callsigns)
    
    with open(output_file, 'w', encoding='utf-8') as f:
        f.write("# 呼号模糊识别库\n")
        f.write(f"# 来源: {os.path.basename(csv_file)}\n")
        f.write(f"# 呼号数量: {len(sorted_callsigns)}\n")
        f.write("# ================================\n\n")
        for callsign in sorted_callsigns:
            f.write(f"{callsign}\n")
    
    print(f"成功提取 {len(sorted_callsigns)} 个唯一呼号")
    print(f"已保存到: {output_file}")
    return len(sorted_callsigns)

if __name__ == "__main__":
    csv_path = r"1.CSV"
    output_path = r"eeeeeeee.txt"
    
    extract_callsigns(csv_path, output_path)
