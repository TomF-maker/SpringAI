package com.example.springai.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserInfoDTO {
    private Long id;
    private String username;
    private String email;
    private String phone;
    private String realName;
    private String avatar;
    /** 积分余额。 */
    private Integer points;
    private Long departmentId;
    private String departmentName;
    /** 所属公司 id（sys_company）。老用户为 null。个人中心可改，见 UpdateUserInfoRequest。 */
    private Long companyId;
    /** 公司名，从 sys_company 翻出来的。 */
    private String companyName;
    /** 统一社会信用代码。老用户为 null，个人中心可以补填。 */
    private String creditCode;
    private Integer userType;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}