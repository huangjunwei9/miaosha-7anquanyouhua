package com.imooc.miaosha.service;

import com.imooc.miaosha.domain.MiaoshaOrder;
import com.imooc.miaosha.domain.MiaoshaUser;
import com.imooc.miaosha.domain.OrderInfo;
import com.imooc.miaosha.redis.MiaoshaKey;
import com.imooc.miaosha.redis.RedisService;
import com.imooc.miaosha.util.MD5Util;
import com.imooc.miaosha.util.UUIDUtil;
import com.imooc.miaosha.vo.GoodsVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Random;


@Service
public class MiaoshaService {

    @Autowired
    GoodsService goodsService;

    @Autowired
    OrderService orderService;

    @Autowired
    RedisService redisService;

    private static char[] ops = new char [] {'+', '-', '*'};

    /** 执行商品秒杀
     * 1.减少库存
     * 2.下订单
     * 3.将订单写入秒杀订单miaosha_order，
     * 4.秒杀成功则返回秒杀订单
     * 注意：这应该放入一个事务中进行，任何一步失败都不能执行商品秒杀
     * */
    @Transactional
    public OrderInfo miaosha(MiaoshaUser miaoshaUser, GoodsVo goodsVo) {
        //减库存，对goods表中的商品id 进行减1操作
        System.out.println("正在减库存");
        goodsService.reduceStock(goodsVo);

        //下订单，并写入到秒杀订单miaosha_order 和 redis缓存中
        System.out.println("正在下订单");
        return orderService.createOrder(miaoshaUser, goodsVo);

    }

    /** 获取秒杀订单，返回秒杀结果。0表示没有订单，其他表示订单id
     * @param userId
     * @param goodsId
     * @return
     * */
    public long getMiaoshaResult(Long userId, long goodsId) {
        MiaoshaOrder miaoshaOrder = orderService.getMiaoshaOrderByUserIdGoodsId(userId, goodsId);

        if(miaoshaOrder != null){
            System.out.println("miaosha函数，提示：已经从缓存中查询到订单"+ miaoshaOrder);
            return miaoshaOrder.getOrderId();
        } else{ //秒杀失败， 失败原因：还没轮到消息队列出队
            System.out.println("miaosha函数，提示：还没有从缓存中查询到订单"+ miaoshaOrder);
            return 0;
        }

    }


    /** 验证传过来的随机字符串是否与缓存中的一致，一致则返回true，不一致则返回false
     * 缓存中的key为userId和goodId，value为随机字符串
     * @param miaoshaUser
     * @param goodsId
     * @param path
     * @return
     */
    public boolean checkPath(MiaoshaUser miaoshaUser, long goodsId, String path) {
        if(miaoshaUser == null || path == null){
            return false;
        }
        String pathOld = redisService.get(MiaoshaKey.getMiaoshaPath, "" + miaoshaUser.getId() + "_" + goodsId, String.class);  //缓存加密随机串
        return path.equals(pathOld);
    }


    /** 校验验证码输入结果
     * @param miaoshaUser
     * @param goodsId
     * @param verifyCode
     * @return
     */
    public boolean checkVerifyCode(MiaoshaUser miaoshaUser, long goodsId, int verifyCode) {
        if(miaoshaUser == null || goodsId < 0){
            return false;
        }

        Integer verifyCodeOld = redisService.get(MiaoshaKey.getMiaoshaVerifyCode, miaoshaUser.getId() + "," + goodsId, Integer.class);  //缓存加密随机串
        if(  verifyCodeOld == null || (verifyCodeOld - verifyCode) != 0  ){
            return false;
        }

        //清除该验证码缓存
        redisService.delete(MiaoshaKey.getMiaoshaVerifyCode, miaoshaUser.getId() + "," + goodsId);
        return true;

    }


    /** 使用UUIDUtil生成随机串，使用md5加密随机串，缓存加密随机串
     * @param miaoshaUser
     * @param goodsId
     * @return
     */
    public String createMiaoshaPath(MiaoshaUser miaoshaUser, long goodsId) {
        if(miaoshaUser == null || goodsId <= 0){
            return null;
        }
        String str = MD5Util.md5(UUIDUtil.uuid() + "123456");
        redisService.set(MiaoshaKey.getMiaoshaPath, "" + miaoshaUser.getId() + "_" + goodsId, str);
        return str;
    }

    /** 生成图形验证码
     * @param miaoshaUser
     * @param goodsId
     * @return
     */
    public BufferedImage createMiaoshaVerifyCode(MiaoshaUser miaoshaUser, long goodsId) {
        if(miaoshaUser == null || goodsId <= 0){
            return null;
        }

        int width = 80;
        int height = 32;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics g = image.getGraphics();
        g.setColor(new Color(0xDCDCDC));
        g.fillRect(0,0, width, height);
        g.setColor(Color.black);
        g.drawRect(0, 0, width - 1, height - 1);
        Random rdm = new Random();
        for(int i=0; i<50; i++){ //在图片上生成50个干扰点
            int x = rdm.nextInt(width);
            int y = rdm.nextInt(height);
            g.drawOval(x, y, 0, 0);
        }
        String verifyCode = generateVerifyCode(rdm);
        g.setColor(new Color(0, 100, 0));
        g.setFont(new Font("Candara", Font.BOLD, 24));
        g.drawString(verifyCode, 8, 24);
        g.dispose();

        int rnd = calc(verifyCode);
        redisService.set(MiaoshaKey.getMiaoshaVerifyCode, miaoshaUser.getId() + "," + goodsId, rnd);
        return image;
    }


    /** 根据表达式计算结果
     * -----------------------分割，利用引擎解决计算问题------------------------------------------
     * 验证图形验证码   用到scriptEnige图形引擎
     * @param verifyCode
     * @return
     */
    private int calc(String verifyCode) {
        try{
            ScriptEngineManager manager = new ScriptEngineManager();
            ScriptEngine engine = manager.getEngineByName("JavaScript");
            return (Integer) engine.eval(verifyCode);

        }catch (Exception e){
            e.printStackTrace();
            return (Integer)null;
        }
    }

    /** 生成随机数学公式验证码表达式
     * 三个随机数
     * + - *
     */
    private String generateVerifyCode(Random rdm) {
        int num1 = rdm.nextInt(10);//生成一个大于0，且小于10的随机数
        int num2 = rdm.nextInt(10);
        int num3 = rdm.nextInt(10);
        char op1 = ops[rdm.nextInt(3)];//定义操作数
        char op2 = ops[rdm.nextInt(3)];

        String exp = "" + num1 + op1 + num2 + op2 + num3; //生成表达式
        return exp;
    }


}












