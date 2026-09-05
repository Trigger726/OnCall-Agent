package org.trigger.opspilot.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {
    @GetMapping({
            "/login", "/incidents", "/assistant", "/alerts", "/cmdb",
            "/on-call", "/runbooks", "/analytics", "/problems", "/audit"
    })
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}
