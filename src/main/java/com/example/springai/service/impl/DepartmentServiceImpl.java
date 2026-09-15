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
import org.springframework.beans.factory.annotation.Value;
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

    /** 注册用户在公司名对不上任何部门时，退让到的默认归属部门名。 */
    @Value("${app.company.headquarters-dept-name:总公司}")
    private String headquartersDeptName;

    @Override
    public Long findIdByName(String deptName) {
        if (!StringUtils.hasText(deptName)) {
            return null;
        }
        SysDepartment dept = departmentMapper.selectOne(
                new QueryWrapper<SysDepartment>()
                        .eq("dept_name", deptName.trim())
                        // 同名部门理论上是脏数据，但真出现时 selectOne 会抛
                        // TooManyResultsException，注册链路不能被它打断
                        .last("LIMIT 1"));
        return dept == null ? null : dept.getId();
    }

    @Override
    public Long resolveRegistrationDeptId(String companyName) {
        // 1. 用户填的公司名正好是一个部门 —— 说明跟组织架构对上了
        Long byCompany = findIdByName(companyName);
        if (byCompany != null) {
            return byCompany;
        }

        // 2. 总公司
        Long headquarters = findIdByName(headquartersDeptName);
        if (headquarters != null) {
            return headquarters;
        }

        // 3. 第一个根部门。走到这里说明「总公司」这个名字在部门表里不存在，
        //    多半是还没建，所以给一条能用的退路而不是直接放弃
        SysDepartment root = departmentMapper.selectOne(
                new QueryWrapper<SysDepartment>()
                        .eq("parent_id", 0)
                        .orderByAsc("sort_order", "id")
                        .last("LIMIT 1"));
        if (root != null) {
            log.warn("⚠️ 未找到名为「{}」的部门（可用 app.company.headquarters-dept-name 配置），"
                            + "本次注册将挂到第一个根部门「{}」(id={})。"
                            + "建议在部门管理里建一个总公司节点。",
                    headquartersDeptName, root.getDeptName(), root.getId());
            return root.getId();
        }

        // 4. 部门表是空的或全都没有根节点 —— 退到"没有部门"，并明确提示，
        //    否则新用户会静默地全部没有部门，而管理页上看起来一切正常
        log.warn("⚠️ 部门表里没有任何根部门（parent_id=0），本次注册的 department_id 为空。"
                + "请在部门管理里建一个总公司节点。");
        return null;
    }

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