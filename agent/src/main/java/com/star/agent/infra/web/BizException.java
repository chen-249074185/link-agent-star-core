package com.star.agent.infra.web;

//错误类，为result文件提供参数
public class BizException extends RuntimeException{
    private final int code;

    public BizException(int code,String msg) {
        super(msg);
        this.code = code;
    }

    public int getCode(){
        return code;
    }
}
