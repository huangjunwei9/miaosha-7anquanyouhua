package com.imooc.miaosha.access;

import com.alibaba.druid.util.StringUtils;
import com.alibaba.fastjson.JSON;
import com.imooc.miaosha.domain.MiaoshaUser;
import com.imooc.miaosha.redis.AccessKey;
import com.imooc.miaosha.redis.RedisService;
import com.imooc.miaosha.result.CodeMsg;
import com.imooc.miaosha.result.Result;
import com.imooc.miaosha.service.MiaoshaUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.OutputStream;


/** 定义拦截器
 *
 */
@Service
public class AccessInterceptor extends HandlerInterceptorAdapter {

    @Autowired
    MiaoshaUserService miaoshaUserService;

    @Autowired
    RedisService redisService;

    /** 方法执行之前，检查用户是否登录，检查访问次数，
     * 1.获取登录用户
     * 2.获取访问次数
     * */
    @Override
    public boolean preHandle(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Object handler)
            throws Exception {
        if(handler instanceof HandlerMethod){
            MiaoshaUser miaoshaUser =getUser(httpServletRequest,httpServletResponse);
            UserContext.setUser(miaoshaUser);

            HandlerMethod hm = (HandlerMethod)handler;
            AccessLimit accessLimit = hm.getMethodAnnotation(AccessLimit.class);//获取方法中的注解AccessLimit
            if(accessLimit == null){//如果没有该注解，则返回true
                return true;
            }
            int seconds = accessLimit.seconds();
            int maxCount = accessLimit.maxCount();
            boolean needLogin = accessLimit.needLogin();

            String key = httpServletRequest.getRequestURI();
            if(needLogin == true){ //需要用户登录，则检查用户是否登录
                if(miaoshaUser == null){
                    Result<String> resultNull = new Result(CodeMsg.SESSION_ERROR);
                    resultNull.setState(-2); //状态：Session过期
                    render(httpServletResponse, resultNull);
                    return false;
                }
                key = "" + key + "_" + miaoshaUser.getId();
            }

            //限流防刷：1.从redis查询访问次数；限制seconds秒内访问次数为maxCount次
            AccessKey ak = AccessKey.withExpire(seconds);
            Integer count = redisService.get(ak, key, Integer.class);
            if(count == null){ //表示过期了
                redisService.set(ak, key, 1); //设置初始值为1
            }else if(count < maxCount){              //没过期，第maxCount次访问时，使缓存值由 maxCount-1 增加到maxCount。第 maxCount + 1 次访问时，缓存值为maxCount，返回超过次数
                redisService.incr(ak, key);          //缓存值加1
            }else{  //没过期，且访问次数超过了
                Result<String> resultNull =  new Result(CodeMsg.MIAO_SHA_ACCESS_LIMIT);
                resultNull.setState(-5); //状态：一定时间内只允许访问有限的次数（5次）
                render(httpServletResponse, resultNull);
                return false;
            }
        }
        return true;
    }

    //给客户端返回的出错信息：SESSION丢失？ 访问超过次数？
    private void render(HttpServletResponse response, Result result) throws Exception{
        response.setContentType("application/json;charset=UTF-8");
        OutputStream outputStream = response.getOutputStream();
        String str = JSON.toJSONString(result);
        outputStream.write(str.getBytes("UTF-8"));
        outputStream.flush();
        outputStream.close();
    }

    /** 获取用户
     *
     * */
    private MiaoshaUser getUser(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse){
        //获取客户端传过来的Parameter和Cookie
        String paramToken = httpServletRequest.getParameter(MiaoshaUserService.COOKIE_NAME_TOKEN);
        String cookieToken = getCookieValue(httpServletRequest, MiaoshaUserService.COOKIE_NAME_TOKEN);

        //如果Cookie和Param中都没有值，则表示没有登录或者过期了，转去登录页面
        if(StringUtils.isEmpty(cookieToken) && StringUtils.isEmpty(paramToken)){
            return null;
        }

        //此时，Cookie和Param中至少有一个有值，优先取param的值
        String token = StringUtils.isEmpty(paramToken) ? cookieToken:paramToken;

        //从redis缓存中获取该Cookie或者Param所对应的用户信息
        MiaoshaUser miaoshaUser = miaoshaUserService.getByToken(httpServletResponse, token);
        return miaoshaUser;
    }

    /** 获取Cookie列表中 name = cookieNameToken 对应的 value值
     *
     */
    private String getCookieValue(HttpServletRequest httpServletRequest, String cookieNameToken) {
        Cookie[] cookies = httpServletRequest.getCookies();
        if(cookies == null || cookies.length <= 0){
            return null;
        }
        for(Cookie cookie:cookies){
            if(cookie.getName().equals(cookieNameToken)){
                return cookie.getValue();
            }
        }
        return null;
    }


}
