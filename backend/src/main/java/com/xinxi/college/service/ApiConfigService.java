package com.xinxi.college.service;

import com.xinxi.college.entity.ApiConfig;

import java.util.List;

public interface ApiConfigService {
    
    List<ApiConfig> getAllConfigs();
    
    ApiConfig getConfigByProvider(String provider);
    
    void saveConfig(ApiConfig config);
    
    void deleteConfig(String provider);
}
