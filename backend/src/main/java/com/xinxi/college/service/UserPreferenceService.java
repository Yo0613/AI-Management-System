package com.xinxi.college.service;

import com.xinxi.college.entity.UserPreference;

public interface UserPreferenceService {
    
    UserPreference getPreferences(Integer userId);
    
    void savePreferences(UserPreference preference);
}
