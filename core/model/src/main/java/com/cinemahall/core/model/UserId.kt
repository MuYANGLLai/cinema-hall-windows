package com.cinemahall.core.model

/**
 * 接缝 4：用户标识抽象（《技术方案评审》§4.2）。
 *
 * 全代码统一使用本类型，**业务字段名不出现 `deviceId`**。
 * 未来换成账号体系时，只需要替换 [UserIdProvider] 的实现，业务代码不动。
 */
@JvmInline
value class UserId(val value: String)
