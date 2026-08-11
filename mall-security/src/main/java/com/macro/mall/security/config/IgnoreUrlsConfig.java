package com.macro.mall.security.config;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * SpringSecurity白名单资源路径配置
 * Created by macro on 2018/11/5.
 */
@Getter
@Setter
public class IgnoreUrlsConfig {

    private List<String> urls = new ArrayList<>();

}
