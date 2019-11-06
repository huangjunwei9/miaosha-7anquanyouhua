package com.imooc.miaosha.redis;

public class AccessKey extends BasePrefix{
    private AccessKey(int expireSeconds, String prefix){
        super(expireSeconds, prefix);
    }

    public static AccessKey access = new AccessKey(5,"access"); //验证码过期时间5秒
    public static AccessKey withExpire(int expireSeconds){      //验证码过期时间expireSeconds秒
        return new AccessKey(expireSeconds, "access");
    }
}
