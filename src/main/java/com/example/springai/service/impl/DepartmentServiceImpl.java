package com.example.springai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.springai.dto.DepartmentRequest;
import com.example.springai.dto.DepartmentTreeDTO;
import com.example.springai.entity.SysDepartment;
import com.example.springai.entity.SysUser;
import com.example.springai.mapper.SysDepartmentMapper;
import com.example.springai.mapper.SysUserMapper;
import com.example.springai.service.DepartmentServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DepartmentServiceImpl implements DepartmentServiceI {

    @Autowired
    private SysDepartmentMapper departmentMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Override
    public List<DepartmentTreeDTO> getDepartmentTree() {
        List<SysDepartment> allDepts = departmentMapper.selectList(
                new QueryWrapper<SysDepartment>().orderByAsc("sort_order", "id")
        );
        return buildTree(allDepts, 0L);
    }

    private List<DepartmentTreeDTO> buildTree(List<SysDepartment> all, Long parentId) {
        return all.stream()
                .filter(d -> d.getParentId() != null && d.getParentId().equals(parentId))
                .map(d -> {
                    DepartmentTreeDTO node = new DepartmentTreeDTO();
                    BeanUtils.copyProperties(d, node);
                    // 获取负责人姓名
                    if (d.getLeaderId() != null) {
                        SysUser leader = userMapper.selectById(d.getLeaderId());
                        if (leader != null) {
                            node.setLeaderName(leader.getRealName());
                        }
                    }
                    node.setChildren(buildTree(all, d.getId()));
                    return node;
                })
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public SysDepartment createDepartment(DepartmentRequest request) {
        // 校验编码唯一
        if (StringUtils.hasText(request.getDeptCode())) {
            SysDepartment existing = departmentMapper.selectOne(
                    new QueryWrapper<SysDepartment>().eq("dept_code", request.getDeptCode())
            );
            if (existing != null) {
                throw new RuntimeException("部门编码已存在");
            }
        }
        SysDepartment dept = new SysDepartment();
        BeanUtils.copyProperties(request, dept);
        if (request.getParentId() == null) {
            dept.setParentId(0L);
        }
        // 如果上级部门不存在，设为根
        if (dept.getParentId() != null && dept.getParentId() > 0) {
            SysDepartment parent = departmentMapper.selectById(dept.getParentId());
            if (parent == null) {
                throw new RuntimeException("上级部门不存在");
            }
        }
        departmentMapper.insert(dept);
        log.info("部门创建成功: {}", dept.getDeptName());
        return dept;
    }

    @Override
    @Transactional
    public SysDepartment updateDepartment(DepartmentRequest request) {
        if (request.getId() == null) {
            throw new RuntimeException("部门ID不能为空");
        }
        SysDepartment dept = departmentMapper.selectById(request.getId());
        if (dept == null) {
            throw new RuntimeException("部门不存在");
        }
        // 校验编码唯一（排除自身）
        if (StringUtils.hasText(request.getDeptCode())) {
            SysDepartment existing = departmentMapper.selectOne(
                    new QueryWrapper<SysDepartment>().eq("dept_code", request.getDeptCode())
                            .ne("id", request.getId())
            );
            if (existing != null) {
                throw new RuntimeException("部门编码已存在");
            }
        }
        // 不能将部门设为自身的子部门
        if (request.getParentId() != null && request.getParentId().equals(request.getId())) {
            throw new RuntimeException("不能将部门设为自身的子部门");
        }
        BeanUtils.copyProperties(request, dept);
        if (request.getParentId() == null) {
            dept.setParentId(0L);
        }
        departmentMapper.updateById(dept);
        log.info("部门更新成功: {}", dept.getDeptName());
        return dept;
    }

    @Override
    @Transactional
    public void deleteDepartment(Long id) {
        SysDepartment dept = departmentMapper.selectById(id);
        if (dept == null) {
            throw new RuntimeException("部门不存在");
        }
        // 检查是否有子部门
        List<SysDepartment> children = departmentMapper.selectList(
                new QueryWrapper<SysDepartment>().eq("parent_id", id)
        );
        if (!children.isEmpty()) {
            throw new RuntimeException("该部门存在子部门，无法删除");
        }
        // 检查是否有用户关联
        List<SysUser> users = userMapper.selectList(
                new QueryWrapper<SysUser>().eq("department_id", id)
        );
        if (!users.isEmpty()) {
            throw new RuntimeException("该部门下存在用户，无法删除");
        }
        departmentMapper.deleteById(id);
        log.info("部门删除成功: {}", dept.getDeptName());
    }

    @Override
    public SysDepartment getDepartmentById(Long id) {
        return departmentMapper.selectById(id);
    }
}