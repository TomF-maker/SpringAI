package com.example.springai.controller;

import com.example.springai.common.Response;
import com.example.springai.dto.DepartmentRequest;
import com.example.springai.dto.DepartmentTreeDTO;
import com.example.springai.entity.SysDepartment;
import com.example.springai.service.DepartmentServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/departments")
@PreAuthorize("hasRole('ADMIN')")  // 仅管理员
public class DepartmentController {

    @Autowired
    private DepartmentServiceI departmentService;

    @GetMapping("/tree")
    public Response<List<DepartmentTreeDTO>> getTree() {
        return Response.success(departmentService.getDepartmentTree());
    }

    @GetMapping("/{id}")
    public Response<SysDepartment> getDepartment(@PathVariable Long id) {
        return Response.success(departmentService.getDepartmentById(id));
    }

    @PostMapping
    public Response<SysDepartment> createDepartment(@RequestBody DepartmentRequest request) {
        return Response.success(departmentService.createDepartment(request));
    }

    @PutMapping
    public Response<SysDepartment> updateDepartment(@RequestBody DepartmentRequest request) {
        return Response.success(departmentService.updateDepartment(request));
    }

    @DeleteMapping("/{id}")
    public Response<Void> deleteDepartment(@PathVariable Long id) {
        departmentService.deleteDepartment(id);
        return Response.success();
    }
}