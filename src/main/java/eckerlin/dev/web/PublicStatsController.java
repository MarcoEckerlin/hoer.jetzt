package eckerlin.dev.web;

import eckerlin.dev.services.ListenerStatsService;
import eckerlin.dev.web.dto.PublicStatsChartView;
import eckerlin.dev.web.dto.PublicStatsView;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class PublicStatsController {

    private final ListenerStatsService listenerStatsService;

    public PublicStatsController(ListenerStatsService listenerStatsService) {
        this.listenerStatsService = listenerStatsService;
    }

    @GetMapping("/stats")
    public String stats(Model model) {
        model.addAttribute("stats", listenerStatsService.buildPublicView());
        return "stats";
    }

    @GetMapping("/api/public/stats")
    @ResponseBody
    public PublicStatsView statsApi() {
        return listenerStatsService.buildPublicView();
    }

    @GetMapping("/api/public/stats/chart")
    @ResponseBody
    public PublicStatsChartView statsChart(@RequestParam(required = false) String range) {
        return listenerStatsService.buildChartView(range);
    }
}
