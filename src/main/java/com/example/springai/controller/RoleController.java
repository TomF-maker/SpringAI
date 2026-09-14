package com.example.springai.controller;

import com.example.springai.common.Response;
import com.example.springai.entity.SysRole;
import com.example.springai.mapper.SysRoleMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/roles")
public class RoleController {

    @Autowired
    private SysRoleMapper roleMapper;

    @GetMapping
    public Response<List<SysRole>> listAllRoles() {
        return Response.success(roleMapper.selectList(null));
    }
}