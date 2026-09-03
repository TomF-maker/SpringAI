package com.example.springai.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class DocumentListDTO {
    private Long id;
    private String title;
    private String fileName;
    private Long fileSize;
    private String fileType;
    private String uploaderName;
    private String departmentName;
    private Integer visibleType;
    private String visibleText;
    private Integer isPublic;
    private Integer chunkCount;
    private Integer viewCount;
    private Integer status;
    private LocalDateTime createdAt;
}