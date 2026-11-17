package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.shopdetail.application.model.request.ClaimTicketRequest;
import com.hmdp.shopdetail.application.model.request.ClaimTicketResolveRequest;
import com.hmdp.shopdetail.application.model.request.GuaranteeClaimCreateRequest;
import com.hmdp.shopdetail.domain.model.GuaranteeClaim;
import com.hmdp.shopdetail.facade.ShopDetailFacade;
import com.hmdp.shopdetail.infrastructure.ticket.ClaimReviewTicketService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.Objects;
import java.util.UUID;

/**
 * 店铺详情 HTTP 接口
 * <p>
 * 接口层，只做参数解析和结果封装，业务逻辑全部委托给 {@link ShopDetailFacade}。
 * 基础路径：/shop/detail
 * 商详主链路入口顺序如下：
 * <ol>
 *   <li>Controller 接收入参，做协议层转换</li>
 *   <li>Facade 屏蔽内部应用层实现</li>
 *   <li>ApplicationService 编排任务链 / 保障申诉状态机</li>
 *   <li>DomainService 负责真正的聚合和衍生规则</li>
 * </ol>
 * </p>
 */
@RestController
@RequestMapping("/shop/detail")
public class ShopDetailController {

    @Resource
    private ShopDetailFacade shopDetailFacade;

    /** 闪赔人工审核工单服务（P8）：属主开工单/查流水，管理端回执驱动状态机收口 */
    @Autowired(required = false)
    private ClaimReviewTicketService claimReviewTicketService;

    void setShopDetailFacade(ShopDetailFacade shopDetailFacade) {
        this.shopDetailFacade = shopDetailFacade;
    }

    /** 商详主接口，返回前端首屏所需的聚合数据。 */
    @GetMapping("/{shopId}")
    public Result getShopDetail(@PathVariable("shopId") Long shopId) {
        return Result.ok(shopDetailFacade.getShopDetail(shopId));
    }

    /**
     * 创建保障申诉单。
     * claimNo 在控制器层生成，确保提交后即使后续进入异步补偿流，也能稳定回查这次申诉。
     * 越权防护（2026-11-17）：申诉单与登录用户绑定，未登录直接拒绝——
     * 拦截器已对 /shop/detail/guarantee/** 要求登录，这里判空是双保险，
     * 替换掉早期 "user == null ? 0L" 的匿名兜底（匿名申诉会造成无主单据）。
     * 场景校验轻版（2026-11-18）：planCode 非空前置校验 + shopId 存在性由应用层校验
     * （快速失败，不再创建注定转人工的申诉单）。
     */
    @PostMapping("/guarantee/claim")
    public Result submitClaim(@RequestBody GuaranteeClaimCreateRequest request) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        if (request.getShopId() == null) {
            return Result.fail("shopId不能为空");
        }
        if (request.getPlanCode() == null || request.getPlanCode().isBlank()) {
            return Result.fail("planCode不能为空");
        }
        GuaranteeClaim claim = new GuaranteeClaim();
        claim.setShopId(request.getShopId());
        claim.setUserId(user.getId());
        claim.setPlanCode(request.getPlanCode());
        claim.setReason(request.getReason());
        claim.setClaimNo("claim-" + UUID.randomUUID().toString().replace("-", ""));
        claim.setStatus("SUBMITTED");
        try {
            return Result.ok(shopDetailFacade.submitGuaranteeClaim(claim));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 查询单个保障申诉的最新处理状态。
     * 越权防护（2026-11-17）：仅属主可查——claimNo 泄露（日志/截图/转发）时，
     * 他人无法借此探到申诉内容与进度；属主不匹配与单据不存在返回同一类业务失败，不泄露存在性。
     */
    @GetMapping("/guarantee/claim/{claimNo}")
    public Result getGuaranteeClaim(@PathVariable("claimNo") String claimNo) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        GuaranteeClaim claim = shopDetailFacade.getGuaranteeClaim(claimNo);
        if (claim == null) {
            return Result.fail("申诉单不存在");
        }
        if (!Objects.equals(user.getId(), claim.getUserId())) {
            return Result.fail("无权查看该申诉单");
        }
        return Result.ok(claim);
    }

    /**
     * 重试保障申诉，超过最大重试次数时转人工审核。
     * 越权防护（2026-11-17）：仅属主可重试——重试会真实驱动赔付状态机（重放节点），
     * 若按 claimNo 不校验属主，拿到单号就能驱动别人的申诉流转，属于横向越权。
     */
    @PostMapping("/guarantee/claim/{claimNo}/retry")
    public Result retryGuaranteeClaim(@PathVariable("claimNo") String claimNo) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        GuaranteeClaim claim = shopDetailFacade.getGuaranteeClaim(claimNo);
        if (claim == null) {
            return Result.fail("申诉单不存在");
        }
        if (!Objects.equals(user.getId(), claim.getUserId())) {
            return Result.fail("无权操作该申诉单");
        }
        return Result.ok(shopDetailFacade.retryGuaranteeClaim(claimNo));
    }

    /**
     * 属主为申诉单开工单（P8）：申诉进入 MANUAL_REVIEW 后补充材料/催办。
     * 越权防护：仅属主可开。
     */
    @PostMapping("/guarantee/claim/{claimNo}/ticket")
    public Result openClaimTicket(@PathVariable("claimNo") String claimNo,
                                  @RequestBody ClaimTicketRequest request) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        if (claimReviewTicketService == null) {
            return Result.fail("工单服务未装配");
        }
        GuaranteeClaim claim = shopDetailFacade.getGuaranteeClaim(claimNo);
        if (claim == null) {
            return Result.fail("申诉单不存在");
        }
        if (!Objects.equals(user.getId(), claim.getUserId())) {
            return Result.fail("无权操作该申诉单");
        }
        if (request.getContent() == null || request.getContent().isBlank()) {
            return Result.fail("content不能为空");
        }
        return Result.ok(claimReviewTicketService.open(claimNo, user.getId(), request.getContent()));
    }

    /** 属主查询申诉单的工单流水（创建时间升序）。越权防护：仅属主可查。 */
    @GetMapping("/guarantee/claim/{claimNo}/tickets")
    public Result listClaimTickets(@PathVariable("claimNo") String claimNo) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        if (claimReviewTicketService == null) {
            return Result.fail("工单服务未装配");
        }
        GuaranteeClaim claim = shopDetailFacade.getGuaranteeClaim(claimNo);
        if (claim == null) {
            return Result.fail("申诉单不存在");
        }
        if (!Objects.equals(user.getId(), claim.getUserId())) {
            return Result.fail("无权查看该申诉单");
        }
        return Result.ok(claimReviewTicketService.findByClaimNo(claimNo));
    }

    /**
     * 管理端回执工单/申诉（P8）：给出人工结论并驱动状态机 resume 收口。
     * <p>管理语义：回执人不要求属主（客服处理他人申诉），登录门槛来自拦截器；
     * 角色门槛 RBAC 是下一步。</p>
     */
    @PostMapping("/guarantee/claim/{claimNo}/ticket/resolve")
    public Result resolveClaimTicket(@PathVariable("claimNo") String claimNo,
                                     @RequestBody ClaimTicketResolveRequest request) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        if (claimReviewTicketService == null) {
            return Result.fail("工单服务未装配");
        }
        if (request.getOutcome() == null
                || (!"APPROVED".equals(request.getOutcome()) && !"REJECTED".equals(request.getOutcome()))) {
            return Result.fail("outcome 必须为 APPROVED 或 REJECTED");
        }
        try {
            claimReviewTicketService.resolve(claimNo, request.getOutcome(), request.getResolution());
            return Result.ok("工单已回执");
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }
}
