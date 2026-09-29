package com.novelforge.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log=LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(Problem.class) ResponseEntity<?> problem(Problem p) { return ResponseEntity.status(p.status).body(Map.of("message",p.getMessage())); }
    @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        var field=e.getBindingResult().getFieldErrors().getFirst();
        return ResponseEntity.badRequest().body(Map.of("message","输入不符合要求："+field.getField()+" "+field.getDefaultMessage()));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class) ResponseEntity<?> json() { return ResponseEntity.badRequest().body(Map.of("message","JSON 结构或字段类型不合法")); }
    @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(Exception e) {
        log.error("Request failed: {}", e.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(Map.of("message","内部错误，原有内容保留；请查看本机日志"));
    }
}
