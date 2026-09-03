package com.example.springai.controller;

import com.example.springai.dto.DepartmentRequest;
import com.example.springai.dto.DepartmentTreeDTO;
import com.example.springai.entity.SysDepartment;
import com.example.springai.service.DepartmentServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/departments")
@PreAuthorize("hasRole('ADMIN')")  // 仅管理员
public class DepartmentController {

    @Autowired
    private DepartmentServiceI departmentService;

    @GetMapping("/tree")
    public Map<String, Object> getTree() {
        List<DepartmentTreeDTO> tree = departmentService.getDepartmentTree();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", tree);
        return result;
    }

    @GetMapping("/{id}")
    public Map<String, Object> getDepartment(@PathVariable Long id) {
        SysDepartment dept = departmentService.getDepartmentById(id);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", dept);
        return result;
    }

    @PostMapping
    public Map<String, Object> createDepartment(@RequestBody DepartmentRequest request) {
        SysDepartment dept = departmentService.createDepartment(request);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "部门创建成功");
        result.put("data", dept);
        return result;
    }

    @PutMapping
    public Map<String, Object> updateDepartment(@RequestBody DepartmentRequest request) {
        SysDepartment dept = departmentService.updateDepartment(request);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "部门更新成功");
        result.put("data", dept);
        return result;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteDepartment(@PathVariable Long id) {
        departmentService.deleteDepartment(id);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "部门删除成功");
        return result;
    }
}