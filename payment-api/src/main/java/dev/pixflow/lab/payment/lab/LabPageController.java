package dev.pixflow.lab.payment.lab;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LabPageController {
    @GetMapping({"/lab", "/lab/"})
    public String index() {
        return "forward:/lab/index.html";
    }
}
