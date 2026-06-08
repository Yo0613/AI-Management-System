package com.xinxi.college.service;

import com.xinxi.college.entity.Employee;

import java.util.List;

public interface EmployeeService {
    
    List<Employee> getEmployees(String keyword);
    
    Employee getEmployeeById(Integer id);
    
    Employee addEmployee(Employee employee);
    
    Employee updateEmployee(Integer id, Employee employee);
    
    void deleteEmployee(Integer id);
    
    int getCount(String keyword);
}
