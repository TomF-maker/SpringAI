package com.example.springai.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 通用的「名字 → 数量」一行。
 *
 * <p>三处统计共用同一个形状：每个公司多少人、每个省多少提问、每个市多少提问。
 * 抽出来是为了让前端的柱状图/排行只需要写一套渲染函数 ——
 * 三份结构一样的 DTO 迟早会长出三种字段名。
 *
 * <p>{@link #name} <b>永远不为 null</b>：SQL 分组出来的 NULL（比如老用户没填公司）
 * 由 Service 层翻译成"未填写"之类的可读文字。前端因此不需要判空。
 */
@Data
@AllArgsConstructor
public class NameCount {

    private String name;
    private long count;
}
