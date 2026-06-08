package com.xinxi.college.service;

import com.xinxi.college.dto.LoginRequest;
import com.xinxi.college.dto.LoginResponse;

public interface UserService {
    LoginResponse login(LoginRequest request);
}
