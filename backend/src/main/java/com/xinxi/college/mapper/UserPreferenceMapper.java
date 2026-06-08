package com.xinxi.college.mapper;

import com.xinxi.college.entity.UserPreference;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserPreferenceMapper {
    
    UserPreference findByUserId(@Param("userId") Integer userId);
    
    int insert(UserPreference preference);
    
    int update(UserPreference preference);
}
