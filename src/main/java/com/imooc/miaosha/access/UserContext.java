package com.imooc.miaosha.access;

import com.imooc.miaosha.domain.MiaoshaUser;

public class UserContext {

    private static ThreadLocal<MiaoshaUser> userHold = new ThreadLocal<MiaoshaUser>();

    public static void setUser(MiaoshaUser user){
        userHold.set(user);
    }

    public static MiaoshaUser getUser(){
        return userHold.get();
    }


}
