package com.example.springai.controller;

import com.example.springai.entity.SysUser;
import com.example.springai.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/test")
public class TestController {

    @Autowired
    private SysUserMapper userMapper;

    @GetMapping("/users")
    public List<SysUser> listUsers() {
        return userMapper.selectList(null);
    }
}