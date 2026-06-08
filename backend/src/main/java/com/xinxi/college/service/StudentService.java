package com.xinxi.college.service;

import com.xinxi.college.entity.Student;

import java.util.List;

public interface StudentService {
    
    List<Student> getStudents(String keyword);
    
    Student getStudentById(Integer id);
    
    Student addStudent(Student student);
    
    Student updateStudent(Integer id, Student student);
    
    void deleteStudent(Integer id);
    
    int getCount(String keyword);
}
