package com.example.springai.service;
import com.example.springai.dto.DepartmentRequest;
import com.example.springai.dto.DepartmentTreeDTO;
import com.example.springai.entity.SysDepartment;
import java.util.List;
public interface DepartmentServiceI {

    List<DepartmentTreeDTO> getDepartmentTree();
    SysDepartment createDepartment(DepartmentRequest request);
    SysDepartment updateDepartment(DepartmentRequest request);
    void deleteDepartment(Long id);
    SysDepartment getDepartmentById(Long id);
}
