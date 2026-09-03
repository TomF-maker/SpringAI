package com.example.springai.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DailyUpload {
    private String date;
    private long count;
}