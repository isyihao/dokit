package com.perye.dokit.controller;

import com.perye.dokit.aop.log.Log;
import com.perye.dokit.config.DataScope;
import com.perye.dokit.dto.RoleSmallDto;
import com.perye.dokit.dto.UserDto;
import com.perye.dokit.dto.UserQueryCriteria;
import com.perye.dokit.entity.User;
import com.perye.dokit.entity.VerificationCode;
import com.perye.dokit.exception.BadRequestException;
import com.perye.dokit.service.*;
import com.perye.dokit.utils.DoKitConstant;
import com.perye.dokit.utils.PageUtil;
import com.perye.dokit.utils.SecurityUtils;
import com.perye.dokit.vo.UserPassVo;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Api(tags = "系统：用户管理")
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final PasswordEncoder passwordEncoder;

    private final UserService userService;

    private final DataScope dataScope;

    private final DeptService deptService;

    private final RoleService roleService;

    private final VerificationCodeService verificationCodeService;

    public UserController(PasswordEncoder passwordEncoder, UserService userService, DataScope dataScope, DeptService deptService, RoleService roleService, VerificationCodeService verificationCodeService) {
        this.passwordEncoder = passwordEncoder;
        this.userService = userService;
        this.dataScope = dataScope;
        this.deptService = deptService;
        this.roleService = roleService;
        this.verificationCodeService = verificationCodeService;
    }


    @Log("导出用户数据")
    @ApiOperation("导出用户数据")
    @GetMapping(value = "/download")
    @PreAuthorize("@dokit.check('user:list')")
    public void download(HttpServletResponse response, UserQueryCriteria criteria) throws IOException {
        userService.download(userService.queryAll(criteria), response);
    }

    @Log("查询用户")
    @ApiOperation("查询用户")
    @GetMapping
    @PreAuthorize("@dokit.check('user:list')")
    public ResponseEntity getUsers(UserQueryCriteria criteria, Pageable pageable){
        Set<Long> deptSet = new HashSet<>();
        Set<Long> result = new HashSet<>();

        if (!ObjectUtils.isEmpty(criteria.getDeptId())) {
            deptSet.add(criteria.getDeptId());
            deptSet.addAll(dataScope.getDeptChildren(deptService.findByPid(criteria.getDeptId())));
        }

        // 数据权限
        Set<Long> deptIds = dataScope.getDeptIds();

        // 查询条件不为空并且数据权限不为空则取交集
        if (!CollectionUtils.isEmpty(deptIds) && !CollectionUtils.isEmpty(deptSet)){

            // 取交集
            result.addAll(deptSet);
            result.retainAll(deptIds);

            // 若无交集，则代表无数据权限
            criteria.setDeptIds(result);
            if(result.size() == 0){
                return new ResponseEntity<>(PageUtil.toPage(null,0),HttpStatus.OK);
            } else {
                return new ResponseEntity<>(userService.queryAll(criteria,pageable),HttpStatus.OK);
            }
            // 否则取并集
        } else {
            result.addAll(deptSet);
            result.addAll(deptIds);
            criteria.setDeptIds(result);
            return new ResponseEntity<>(userService.queryAll(criteria,pageable),HttpStatus.OK);
        }
    }

    @Log("新增用户")
    @ApiOperation("新增用户")
    @PostMapping
    @PreAuthorize("@dokit.check('user:add')")
    public ResponseEntity create(@Validated @RequestBody User resources){
        checkLevel(resources);
        return new ResponseEntity<>(userService.create(resources),HttpStatus.CREATED);
    }

    @Log("修改用户")
    @ApiOperation("修改用户")
    @PutMapping
    @PreAuthorize("@dokit.check('user:edit')")
    public ResponseEntity update(@Validated(User.Update.class) @RequestBody User resources){
        checkLevel(resources);
        userService.update(resources);
        return new ResponseEntity(HttpStatus.NO_CONTENT);
    }

    @Log("删除用户")
    @ApiOperation("删除用户")
    @DeleteMapping(value = "/{id}")
    @PreAuthorize("@dokit.check('user:del')")
    public ResponseEntity delete(@PathVariable Long id){
        UserDto user = userService.findByName(SecurityUtils.getUsername());
        Integer currentLevel =  Collections.min(roleService.findByUsersId(user.getId()).stream().map(RoleSmallDto::getLevel).collect(Collectors.toList()));
        Integer optLevel =  Collections.min(roleService.findByUsersId(id).stream().map(RoleSmallDto::getLevel).collect(Collectors.toList()));
        if (currentLevel > optLevel) {
            throw new BadRequestException("角色权限不足");
        }
        userService.delete(id);
        return new ResponseEntity(HttpStatus.OK);
    }

    @ApiOperation("修改密码")
    @PostMapping(value = "/updatePass")
    public ResponseEntity updatePass(@RequestBody UserPassVo passVo){
        UserDto user = userService.findByName(SecurityUtils.getUsername());
        if(!passwordEncoder.matches(passVo.getOldPass(), user.getPassword())){
            throw new BadRequestException("修改失败，旧密码错误");
        }
        if(passwordEncoder.matches(passVo.getNewPass(), user.getPassword())){
            throw new BadRequestException("新密码不能与旧密码相同");
        }
        userService.updatePass(user.getUsername(),passwordEncoder.encode(passVo.getNewPass()));
        return new ResponseEntity(HttpStatus.OK);
    }

    @ApiOperation("修改头像")
    @PostMapping(value = "/updateAvatar")
    public ResponseEntity updateAvatar(@RequestParam MultipartFile file){
        userService.updateAvatar(file);
        return new ResponseEntity(HttpStatus.OK);
    }

    @Log("修改邮箱")
    @ApiOperation("修改邮箱")
    @PostMapping(value = "/updateEmail/{code}")
    public ResponseEntity updateEmail(@PathVariable String code,@RequestBody User user){
        UserDto userDto = userService.findByName(SecurityUtils.getUsername());
        if(!passwordEncoder.matches(user.getPassword(), userDto.getPassword())){
            throw new BadRequestException("密码错误");
        }
        VerificationCode verificationCode = new VerificationCode(code, DoKitConstant.RESET_MAIL,"email",user.getEmail());
        verificationCodeService.validated(verificationCode);
        userService.updateEmail(userDto.getUsername(),user.getEmail());
        return new ResponseEntity(HttpStatus.OK);
    }

    /**
     * 如果当前用户的角色级别低于创建用户的角色级别，则抛出权限不足的错误
     * @param resources /
     */
    private void checkLevel(User resources) {
        UserDto user = userService.findByName(SecurityUtils.getUsername());
        Integer currentLevel =  Collections.min(roleService.findByUsersId(user.getId()).stream().map(RoleSmallDto::getLevel).collect(Collectors.toList()));
        Integer optLevel = roleService.findByRoles(resources.getRoles());
        if (currentLevel > optLevel) {
            throw new BadRequestException("角色权限不足");
        }
    }
}

