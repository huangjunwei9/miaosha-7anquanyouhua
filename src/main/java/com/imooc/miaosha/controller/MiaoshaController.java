package com.imooc.miaosha.controller;

import com.imooc.miaosha.access.AccessLimit;
import com.imooc.miaosha.domain.MiaoshaOrder;
import com.imooc.miaosha.domain.MiaoshaUser;
import com.imooc.miaosha.domain.OrderInfo;
import com.imooc.miaosha.rabbitmq.MQSender;
import com.imooc.miaosha.rabbitmq.MiaoshaMessage;
import com.imooc.miaosha.redis.AccessKey;
import com.imooc.miaosha.redis.GoodsKey;
import com.imooc.miaosha.redis.MiaoshaKey;
import com.imooc.miaosha.redis.RedisService;
import com.imooc.miaosha.result.CodeMsg;
import com.imooc.miaosha.result.Result;
import com.imooc.miaosha.service.GoodsService;
import com.imooc.miaosha.service.MiaoshaService;
import com.imooc.miaosha.service.OrderService;
import com.imooc.miaosha.util.MD5Util;
import com.imooc.miaosha.util.UUIDUtil;
import com.imooc.miaosha.vo.GoodsVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/* 秒杀进行中，所使用的类 */
@Controller
@RequestMapping(value="/miaosha")
public class MiaoshaController implements InitializingBean {

    @Autowired
    GoodsService goodsService;

    @Autowired
    OrderService orderService;

    @Autowired
    MiaoshaService miaoshaService;

    @Autowired
    RedisService redisService;

    @Autowired
    MQSender mqSender;

    private static Logger log = LoggerFactory.getLogger(GoodsController.class);//

    //可优化成ConCurrentHashMap
    private Map<Long, Boolean> localOverMap = new HashMap<Long, Boolean>(); //(内存标记)本地存储：商品id---库存不足？ 足够则false，不足则true

    /** 系统初始化：
    * 1.加载商品所有信息（包括秒杀时的所有商品信息）
    * 2.把秒杀商品的库存存入redis缓存
    * */
    @Override
    public void afterPropertiesSet() throws Exception {
        System.out.println("系统初始化中。。。");
        List<GoodsVo> goodsList = goodsService.listGoodsVo();
        if(goodsList == null){
            return ;
        }
        //把数据库中秒杀商品的秒杀库存 放入redis缓存
        for(GoodsVo goods : goodsList){
            redisService.set(GoodsKey.getMiaoshaGoodsStock, "" + goods.getId(), goods.getStockCount());
            if(goods.getStockCount() > 0) {
                localOverMap.put(goods.getId(), false);//本地存储：商品id---库存不足？ 足够则false，不足则true
            }else{
                localOverMap.put(goods.getId(), true);
            }
            System.out.println("id = " + goods.getId() + ", 库存 = " + goods.getStockCount());
        }
        System.out.println("系统初始化完毕！");

    }


    /**
     * 响应“立即秒杀”，普通方法，不使用消息队列方法
     * */
//    @RequestMapping(value = "/do_miaosha", method = RequestMethod.POST)
//    @ResponseBody
//    public Result<OrderInfo> miaoshaDo(Model model, MiaoshaUser miaoshaUser, @RequestParam("goodsId")long goodsId){
//        model.addAttribute("user", miaoshaUser);
//
//        if(miaoshaUser == null){
//            Result<OrderInfo> resultNull =  new Result(CodeMsg.SESSION_ERROR);
//            resultNull.setState(1); //状态：出错
//            return resultNull;
//        }
//
//        //判断库存：按照商品id获取商品的库存，判断库存是否符合用户需求
//        System.out.println("goodsId = "+goodsId);
//        GoodsVo goodsVo = goodsService.getGoodsVoByGoodsId(goodsId);
//        int stockCount = goodsVo.getStockCount();
//        if(stockCount <= 0){
//            model.addAttribute("errmsg", CodeMsg.MIAO_SHA_NO_STOCK.getMsg());//返回信息“库存不足”
//            Result<OrderInfo> resultNull =  new Result(CodeMsg.MIAO_SHA_NO_STOCK);
//            resultNull.setState(1); //状态：出错
//            return resultNull;
//        }
//
//        /* 防止重复秒杀同一商品：
//        * 判断是否已经秒杀到了，用于防止一个人秒杀多个商品，即：一个用户对于一种商品只能秒杀一次
//        * 1.根据用户ID和商品Id，获取秒杀订单
//        * 2.如果秒杀订单不是空，说明用户已经秒杀过了，返回错误信息“重复秒杀”
//        * 3.如果商品有库存，而且用户并没有秒杀过，则可执行商品秒杀
//        * */
//        MiaoshaOrder miaoshaOrder = orderService.getMiaoshaOrderByUserIdGoodsId(miaoshaUser.getId(), goodsId);
//        if(miaoshaOrder != null){ //如果秒杀订单不是空，说明用户已经秒杀过了，返回错误信息“重复秒杀”
//            model.addAttribute("errmsg", CodeMsg.MIAO_SHA_REPEAT.getMsg());
//            System.out.println("不允许重复秒杀同一个商品");
//            Result<OrderInfo> resultNull =  new Result(CodeMsg.MIAO_SHA_REPEAT);
//            resultNull.setState(1); //状态：出错
//            return resultNull;
//        }
//
//        /* 执行商品秒杀：
//        * 1.减少库存
//        * 2.下订单
//        * 3.将订单写入秒杀订单miaosha_order和订单order_info，
//        * 4.秒杀成功则返回秒杀订单
//        * 注意：这应该放入一个事务中进行，任何一步失败都不能执行商品秒杀
//        *
//        * */
//        OrderInfo orderInfo = miaoshaService.miaosha(miaoshaUser, goodsVo);
//        System.out.println("订单id = "+ orderInfo.getId());
//        model.addAttribute("orderInfo", orderInfo); //返回订单所有信息
//        model.addAttribute("goods", goodsVo);       //返回商品所有信息
//        return new Result(orderInfo);
//    }


    /** 响应“立即秒杀”，用消息队列的方式
    * 1.系统初始化时，把商品库存数量加载到redis缓存中
    * 2.收到请求，Redis预减库存，如果库存不足则直接返回"库存不足"，否则进入3
    * 3.请求入队、立即返回排队中
    * 4.请求出队，生成订单，减少库存
    * 5.客户端轮询，询问服务端到底是秒杀成功还是秒杀失败
    * */
    @RequestMapping(value = "/{path}/do_miaosha", method = RequestMethod.POST)
    @ResponseBody
    public Result<OrderInfo> miaoshaDoMQ(Model model, MiaoshaUser miaoshaUser, @RequestParam("goodsId")long goodsId, @PathVariable("path") String path){
        model.addAttribute("user", miaoshaUser);
        if(miaoshaUser == null){
            Result<OrderInfo> resultNull =  new Result(CodeMsg.SESSION_ERROR);
            resultNull.setState(-2); //状态：Session过期
            return resultNull;
        }

        //验证path随机串是否与缓存中的一致，一致返回true，不一致则返回false
        boolean check = miaoshaService.checkPath(miaoshaUser, goodsId, path);

        //!false即true，此时表示随机串不一致，返回“请求非法”
        if(!check){
            Result<OrderInfo> resultNull =  new Result(CodeMsg.MIAO_SHA_VERICODE);
            resultNull.setState(-3); //状态：验证请求非法
            return resultNull;
        }

        //从本地查看库存不足？ false为足够，true为不足。
        boolean over = localOverMap.get(goodsId);
        if(over){
            Result<OrderInfo> resultNull =  new Result(CodeMsg.MIAO_SHA_NO_STOCK);
            resultNull.setState(1); //状态：出错
            return resultNull;
        }

        //2.预减缓存中的库存，如果库存不足则直接返回"库存不足"，否则继续
        long stock = redisService.decr(GoodsKey.getMiaoshaGoodsStock,"" + goodsId);
        if(stock < 0){
            localOverMap.put(goodsId, true); //内存标记：该商品的库存不足为真

            System.out.println("商品id = "+ goodsId + "， 缓存中的库存 = " + stock + "，该商品的库存不足！");
            Result<OrderInfo> resultNull =  new Result(CodeMsg.MIAO_SHA_NO_STOCK);
            resultNull.setState(1); //状态：出错
            return resultNull;
        }

         /* 判断是否已经秒杀到了，防止重复秒杀同一商品：
         * 即：一个用户对于一种商品只能秒杀一次
         * 1.根据用户ID和商品Id，获取秒杀订单
         * 2.如果秒杀订单不是空，说明用户已经秒杀过了，返回错误信息“重复秒杀”
         * 3.如果商品有库存，而且用户并没有秒杀过，则可执行商品秒杀
         * */
        MiaoshaOrder miaoshaOrder = orderService.getMiaoshaOrderByUserIdGoodsId(miaoshaUser.getId(), goodsId);
        if(miaoshaOrder != null){ //如果秒杀订单不是空，说明用户已经秒杀过了，返回错误信息“重复秒杀”
            model.addAttribute("errmsg", CodeMsg.MIAO_SHA_REPEAT.getMsg());
            System.out.println("不允许重复秒杀同一个商品");
            Result<OrderInfo> resultNull =  new Result(CodeMsg.MIAO_SHA_REPEAT);
            resultNull.setState(1); //状态：出错
            return resultNull;
        }

        //3.请求入队，此时还未生成订单，立即返回排队中
        MiaoshaMessage miaoshaMessage= new MiaoshaMessage();
        miaoshaMessage.setMiaoshaUser(miaoshaUser);
        miaoshaMessage.setGoodsId(goodsId);
        mqSender.sendMiaoshaMessage(miaoshaMessage);//用户信息，秒杀商品id
        {
            Result<OrderInfo> resultNull =  new Result(CodeMsg.MIAO_SHA_REPEAT);
            resultNull.setState(0); //state=0表示正在排队中
            return resultNull;
        }

    }

    /** 排队时的客户端----轮询访问是否秒杀成功
     * 1.查看是否生成了订单，没有订单表示：排队中的用户队列还没有出队处理订单，放回state:0，继续轮询访问
     * 2.如果生成了订单，则表示秒杀成功，返回订单的id
     * 3.库存不足，返回秒杀失败
     * */
    @AccessLimit(seconds = 5, maxCount = 8, needLogin = true)//限流拦截器,5秒内最多8次，且用户需要为登录状态。------实际上该限流算法存在缺陷
    @RequestMapping(value = "/result", method = RequestMethod.GET)
    @ResponseBody
    public Result<Long> miaoshaResult(Model model, MiaoshaUser miaoshaUser, @RequestParam("goodsId")long goodsId) {
        model.addAttribute("user", miaoshaUser);
        if (miaoshaUser == null) {
            Result<Long> resultNull = new Result(CodeMsg.SESSION_ERROR);
            resultNull.setState(-2); //状态：Session过期
            return resultNull;
        }

        System.out.println("轮询查询服务器，商品id = " + goodsId + "，用户id = " + miaoshaUser.getId());
        long result = miaoshaService.getMiaoshaResult(miaoshaUser.getId(), goodsId);// 返回orderId，如果result为0，则返回0
        {
            System.out.println("result（Controller）订单id = " + result);
            Result<Long> resultNull = new Result(result);//状态： 1.成功data.data=result=订单id ; 2.排队中data.data=0
            resultNull.setState(0); //状态：没有异常
            return resultNull;
        }

    }


    /** 请求提前获取某商品的秒杀接口地址-----生成动态秒杀接口地址
     *
     * */
    @AccessLimit(seconds = 5, maxCount = 5, needLogin = true) //限流拦截器,5秒内最多5次，且用户需要为登录状态。------实际上该限流算法存在缺陷
    @RequestMapping(value = "/path", method = RequestMethod.GET)
    @ResponseBody
    public Result<String> getGoodsListMQPath(HttpServletRequest httpServletRequest, MiaoshaUser miaoshaUser, @RequestParam("goodsId")long goodsId,
                                             @RequestParam(value="verifyCode", defaultValue="0")int verifyCode) {
        if (miaoshaUser == null) {
            Result<String> resultNull = new Result(CodeMsg.SESSION_ERROR);
            resultNull.setState(-2); //状态：Session过期
            return resultNull;
        }

        //限流防刷：1.从redis查询访问次数；限制访问次数为5次
//        String url = httpServletRequest.getRequestURI();//获取当前访问路径---接口
//        Integer count = redisService.get(AccessKey.access, "" + url + "_" + miaoshaUser.getId(), Integer.class);
//        if(count == null){
//            redisService.set(AccessKey.access, "" + url + "_" + miaoshaUser.getId(), 1);    //设置初始值为1
//        }else if(count < 5){ //第五次访问时，使缓存值由4增加到5。  第六次访问时，缓存之为5，返回超过次数
//            redisService.incr(AccessKey.access, "" + url + "_" + miaoshaUser.getId());             //缓存值加1
//        }else{
//            Result<String> resultNull =  new Result(CodeMsg.MIAO_SHA_ACCESS_LIMIT);
//            resultNull.setState(-5); //状态：一定时间内只允许访问有限的次数（5次）
//            return resultNull;
//        }

        //校验图片验证码输入结果
        boolean check = miaoshaService.checkVerifyCode(miaoshaUser, goodsId, verifyCode);
        if(!check){
            Result<String> resultNull =  new Result(CodeMsg.MIAO_SHA_VERICODE_IMAGE);
            resultNull.setState(-4); //状态：验证请求非法
            return resultNull;
        }

        String path = miaoshaService.createMiaoshaPath(miaoshaUser, goodsId);
        return new Result(0,"加密随机串",path);
    }


    /**生成图片验证码 verifyCode
     *
     * */
    @RequestMapping(value = "/verifyCode", method = RequestMethod.GET)
    @ResponseBody
    public Result<String> getGoodsListMQImg(HttpServletResponse httpServletResponse, Model model, MiaoshaUser miaoshaUser, @RequestParam("goodsId")long goodsId) {
        model.addAttribute("user", miaoshaUser);
        if (miaoshaUser == null) {
            Result<String> resultNull = new Result(CodeMsg.SESSION_ERROR);
            resultNull.setState(-2); //状态：Session过期
            return resultNull;
        }
        BufferedImage image =miaoshaService.createMiaoshaVerifyCode(miaoshaUser,goodsId);

        try{
            OutputStream outputStream = httpServletResponse.getOutputStream();
            ImageIO.write(image, "JPEG", outputStream);
            outputStream.flush();
            outputStream.close();
            return null;
        }catch (Exception e){
            e.printStackTrace();
            Result<String> resultNull =  new Result(CodeMsg.MIAO_SHA_IMAGE);
            resultNull.setState(-4); //状态：验证请求非法
            return resultNull;
        }
    }


}












