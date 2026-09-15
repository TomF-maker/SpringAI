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

    /**
     * 按部门名称精确查找 id。
     *
     * <p>现有代码里查重一律按 {@code dept_code}，没有按名称查的方法 ——
     * 注册时拿用户填的公司名去碰组织架构要用这一条。
     *
     * @param deptName 部门名称，前后空白会被裁掉
     * @return 命中的部门 id；没有同名部门（或入参为空白）时返回 null
     */
    Long findIdByName(String deptName);

    /**
     * 解析注册用户的初始归属部门。按四级顺序退让：
     * <ol>
     *   <li>公司名本身就是某个部门名 → 用它（用户填的公司跟组织架构对上了）</li>
     *   <li>否则用「总公司」（{@code app.company.headquarters-dept-name}，默认「总公司」）</li>
     *   <li>再否则取第一个根部门（{@code parent_id=0} 按 sort_order、id 排序的第一个）</li>
     *   <li>部门表里连根部门都没有 → 返回 null，并打 WARN</li>
     * </ol>
     *
     * <p><b>绝不抛异常。</b>这是注册链路，部门表没配好不该把新用户挡在门外 ——
     * 退到底就是"没有部门"，和现在注册不设 departmentId 的行为一致（不会更差）。
     *
     * @param companyName 用户填的公司名，可为 null
     * @return 部门 id，或 null
     */
    Long resolveRegistrationDeptId(String companyName);
}
