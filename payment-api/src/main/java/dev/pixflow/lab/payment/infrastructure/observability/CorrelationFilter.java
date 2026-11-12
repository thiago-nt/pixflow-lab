package dev.pixflow.lab.payment.infrastructure.observability;

import jakarta.servlet.*;import jakarta.servlet.http.*;import org.slf4j.*;import org.springframework.stereotype.Component;import org.springframework.web.filter.OncePerRequestFilter;import java.io.IOException;import java.util.UUID;

@Component public class CorrelationFilter extends OncePerRequestFilter {
 private static final Logger log=LoggerFactory.getLogger(CorrelationFilter.class);
 protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{var supplied=req.getHeader("X-Correlation-ID");var id=(supplied==null||supplied.isBlank())?UUID.randomUUID().toString():supplied.substring(0,Math.min(100,supplied.length()));long start=System.nanoTime();MDC.put("correlationId",id);res.setHeader("X-Correlation-ID",id);try{chain.doFilter(req,res);}finally{long duration=(System.nanoTime()-start)/1_000_000;log.atInfo().addKeyValue("operation","http_request").addKeyValue("method",req.getMethod()).addKeyValue("path",req.getRequestURI()).addKeyValue("status",res.getStatus()).addKeyValue("outcome",res.getStatus()<400?"success":"error").addKeyValue("durationMs",duration).log("HTTP request completed");MDC.clear();}}
}
