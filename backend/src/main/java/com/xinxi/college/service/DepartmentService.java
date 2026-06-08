package com.xinxi.college.service;

import com.xinxi.college.dto.DepartmentQueryDTO;
import com.xinxi.college.entity.Department;

import java.util.Map;

public interface DepartmentService {
    Map<String, Object> getDepartments(DepartmentQueryDTO query);
    
    Department getDepartmentById(Integer id);
    
    void addDepartment(Department department);
    
    Department updateDepartment(Integer id, Department department);
    
    void deleteDepartment(Integer id);
}
