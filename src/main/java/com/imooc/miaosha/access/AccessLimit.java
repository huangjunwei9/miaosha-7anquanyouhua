package com.imooc.miaosha.access;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

//该注解作用：限制访问次数
//@AccessLimit(seconds = 5, maxCount = 8, needLogin = true)//限流拦截器,5秒内最多8次，且用户需要为登录状态。------实际上该限流算法存在缺陷
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AccessLimit {
    int seconds();
    int maxCount();
    boolean needLogin() default true;//默认返回值为true
}
