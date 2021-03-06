package com.yunweibang.auth.handler;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.security.auth.login.AccountLockedException;
import javax.security.auth.login.FailedLoginException;

import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.handlers.ArrayListHandler;
import org.apache.commons.dbutils.handlers.MapHandler;
import org.apache.commons.dbutils.handlers.MapListHandler;
import org.apache.commons.lang3.StringUtils;
import org.apereo.cas.authentication.AuthenticationHandlerExecutionResult;
import org.apereo.cas.authentication.Credential;
import org.apereo.cas.authentication.MessageDescriptor;
import org.apereo.cas.authentication.PreventedException;
import org.apereo.cas.authentication.exceptions.AccountDisabledException;
import org.apereo.cas.authentication.handler.support.AbstractPreAndPostProcessingAuthenticationHandler;
import org.apereo.cas.authentication.principal.PrincipalFactory;
import org.apereo.cas.services.ServicesManager;
import org.apereo.inspektr.common.web.ClientInfo;
import org.apereo.inspektr.common.web.ClientInfoHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSONObject;
import com.yunweibang.auth.exceptions.AccountDisabledOrExpiredException;
import com.yunweibang.auth.exceptions.DynamicCodeExpiredException;
import com.yunweibang.auth.exceptions.DynamicCodeIsNullException;
import com.yunweibang.auth.exceptions.LDAPNotSearchUserException;
import com.yunweibang.auth.exceptions.LDAPOUMismatchingException;
import com.yunweibang.auth.exceptions.LDAPSearchMoreUserException;
import com.yunweibang.auth.exceptions.TooManyCountException;
import com.yunweibang.auth.model.UsernamePassDynamicPassCredential;
import com.yunweibang.auth.service.UserService;
import com.yunweibang.auth.utils.AESUtil;
import com.yunweibang.auth.utils.BCrypt;
import com.yunweibang.auth.utils.Constants;
import com.yunweibang.auth.utils.DateUtil;
import com.yunweibang.auth.utils.GoogleAuthenticatorUtils;
import com.yunweibang.auth.utils.IPZone;
import com.yunweibang.auth.utils.JdbcUtils;
import com.yunweibang.auth.utils.JsonUtil;
import com.yunweibang.auth.utils.LDAPUtil;
import com.yunweibang.auth.utils.PrincipalAttributeUtils;
import com.yunweibang.auth.utils.QQWry;
import com.yunweibang.auth.utils.TxQueryRunner;

public class CustomerHandler extends AbstractPreAndPostProcessingAuthenticationHandler {

	private static Logger logger = LoggerFactory.getLogger(CustomerHandler.class);
	public static final String MYSQL_LOGIN = "mysql";
	public static final String LDAP_LOGIN = "ldap";
	public static final String LDAPS_LOGIN = "ldaps";

	public CustomerHandler(String name, ServicesManager servicesManager, PrincipalFactory principalFactory,
			Integer order) {
		super(name, servicesManager, principalFactory, order);
	}

	@SuppressWarnings({ "deprecation" })
	protected AuthenticationHandlerExecutionResult doAuthentication(Credential credential)
			throws GeneralSecurityException, PreventedException {
		UsernamePassDynamicPassCredential usernamePasswordCredentia = (UsernamePassDynamicPassCredential) credential;

		String username = usernamePasswordCredentia.getUsername();
		String password = usernamePasswordCredentia.getPassword();
		String code = usernamePasswordCredentia.getCode();
		String address = null;
		ClientInfo clientInfo = ClientInfoHolder.getClientInfo();
		logger.info("clientInfo=" + JsonUtil.toJson(clientInfo));
		TxQueryRunner tx = new TxQueryRunner();
		String logsql = " insert into log_login(account,status,operation,content,login_type,ip,city,ctime)  values(?,?,?,?,?,?,?,?) ";
		address = getCityAddress(clientInfo, address);
		if (StringUtils.isBlank(username)) {
			throw new AccountDisabledException();
		} else if (StringUtils.isBlank(password)) {
			throw new AccountLockedException();
		} else {
			QueryRunner qr = new QueryRunner(JdbcUtils.getDs());
			String logloginsql = "select ctime from log_login where ctime > ? and status=? and account =? order by ctime desc ";
			Calendar calendar = Calendar.getInstance();
			calendar.set(Calendar.HOUR_OF_DAY, calendar.get(Calendar.HOUR_OF_DAY) - 6);
			Date lastdate = calendar.getTime();
			Object[] logloginparams = new Object[] { lastdate, Constants.LOGIN_STATUS_FAIL, username };
			List<Map<String, Object>> counts = null;
			try {
				counts = qr.query(logloginsql, logloginparams, new MapListHandler());
				if (counts != null) {
					SimpleDateFormat sdfTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
					boolean flag = counts.size() >= 5 && new Date().getTime()
							- sdfTime.parse(counts.get(0).get("ctime").toString()).getTime() < 10 * 60 * 1000;
					logger.info("loglogin flag= {}", flag);
					if (flag) {
						UserService userservice = new UserService();
						String title = "安全警告通知";
						String msg = "已连续5次输错登录密码，账号已被锁定10分钟。";
						userservice.sendMessageToUser(username, title, msg);
						throw new TooManyCountException();
					}
				}
			} catch (SQLException e2) {
				e2.printStackTrace();
			} catch (ParseException e) {
				e.printStackTrace();
			}
			String pwd = BCrypt.hashpw(password, BCrypt.gensalt());

			Map<String, Object> ssoAuth = null;

			// 查询sso auth
			String sql = "select * from sso_auth where enable=1";

			try {
				ssoAuth = qr.query(sql, new MapHandler());
			} catch (SQLException e1) {
				logger.error(e1.getMessage());
			}

			if ("admin".equals(username) || "local".equals(ssoAuth.get("type").toString())) {

				String usersql = "select * from ri_user where account = ?";
				Object[] userparams = new Object[] { username };
				Map<String, Object> usermap = null;
				try {
					usermap = qr.query(usersql, new MapHandler(), userparams);
					if (usermap != null && usermap.containsKey("id")) {
						Map<String, Object> result = new HashMap<String, Object>();
						Set<String> sets = (Set<String>) usermap.keySet();
						Set<String> principalAttributesets = PrincipalAttributeUtils.getPrincipalAttributes();
						boolean b = sets.containsAll(principalAttributesets);
						if (b) {
							for (String value : principalAttributesets) {
								result.put(value, usermap.get(value));
							}
						} else {
							throw new RuntimeException("cas.principal.attributes 属性错误");
						}
						Date etime = null;
						if (usermap.get("etime") != null) {
							SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
							etime = format.parse(usermap.get("etime").toString());
						}
						if (!"启用".equals((String) usermap.get("status"))
								|| (etime != null && etime.getTime() < new Date().getTime())) {
							throw new AccountDisabledOrExpiredException();
						}
						boolean passSucess = false;
						if (BCrypt.checkpw(password, String.valueOf(usermap.get("pass")))) {
							Date d = new Date();
							Object[] logparams = new Object[] { usermap.get("account").toString(),
									Constants.LOGIN_STATUS_SUCCESS, Constants.LOGIN, Constants.LOGIN_SUCCESS,
									MYSQL_LOGIN, clientInfo.getClientIpAddress(), address, d };
							try {
								int logresult = tx.update(logsql, logparams);
								if (logresult <= 0) {
									throw new RuntimeException("插入登录日志失败");
								}
							} catch (Exception ex) {
								throw new RuntimeException(ex);
							}
							passSucess = true;

						}
						if (!passSucess) {
							insertfailurelog(username, clientInfo, Constants.LOGIN_FAIL, logsql, tx, address,
									MYSQL_LOGIN);
							throw new FailedLoginException();
						}
						if ("启用".equals((String) usermap.get("sso_mfa"))) {
							String secretKey = (String) usermap.get("mfa_secretkey");
							secretKey = AESUtil.decrypt(secretKey, Constants.MD5_SALT);
							if (StringUtils.isBlank(code)) {
								throw new DynamicCodeIsNullException();
							} else {
								if (StringUtils.isBlank(secretKey)) {
									throw new DynamicCodeExpiredException();
								} else if (!GoogleAuthenticatorUtils.verify(secretKey, code)) {
									throw new DynamicCodeExpiredException();
								} else {
									if (passSucess) {
										// 允许登录，并且通过this.principalFactory.createPrincipal 来返回用户属性
										List<MessageDescriptor> list = new ArrayList<>();
										return createHandlerResult(credential,
												this.principalFactory.createPrincipal(username, result), list);
									} else {
										throw new DynamicCodeExpiredException();
									}
								}
							}
						}
						if (passSucess) {
							// 允许登录，并且通过this.principalFactory.createPrincipal 来返回用户属性
							List<MessageDescriptor> list = new ArrayList<>();
							return createHandlerResult(credential,
									this.principalFactory.createPrincipal(username, result), list);
						}
						throw new FailedLoginException();

					} else {
						insertfailurelog(username + "(未知)", clientInfo, Constants.LOGIN_FAIL, logsql, tx, address,
								MYSQL_LOGIN);
						throw new FailedLoginException();
					}
				} catch (AccountDisabledOrExpiredException e) {
					e.printStackTrace();
					throw new AccountDisabledOrExpiredException();
				} catch (DynamicCodeIsNullException e) {
					e.printStackTrace();
					throw new DynamicCodeIsNullException();
				} catch (DynamicCodeExpiredException e) {
					e.printStackTrace();
					throw new DynamicCodeExpiredException();
				} catch (FailedLoginException e) {
					e.printStackTrace();
					throw new FailedLoginException();
				} catch (Exception e) {
					e.printStackTrace();
				}
			} else if ("ldap".equals(ssoAuth.get("type").toString())) {

				try {

					// 获取连接ldap信息
					String ldapServer = (String) ssoAuth.get("ldap_server");
					String ldapPort = (String) ssoAuth.get("ldap_port");
					String filter = (String) ssoAuth.get("user_filter");
					String baseDn = (String) ssoAuth.get("base_dn");
					String mapMysql = (String) ssoAuth.get("map_mysql");
					String bindUser = (String) ssoAuth.get("bind_user");
					String userOu = (String) ssoAuth.get("user_ou");
					String bindPass = (String) ssoAuth.get("bind_pass");
					boolean flagssl = (Boolean) ssoAuth.get("use_ssl");// 判断是否启用ldaps认证

					String url = "ldap://" + ldapServer + ":" + ldapPort;

					if (flagssl) { // 启用 ldapssl
						url = "ldaps://" + ldapServer + ":" + ldapPort;
					}

					Map<String, Object> mapmysql = JSONObject.parseObject(mapMysql); // mapMysql 转化成map

					Set<String> setmysql = mapmysql.keySet(); // 获取map的键

					boolean bindUserConnstate = false; // user 登录状态

					Map<String, Object> map = new HashMap<>();

					if (flagssl) { // 启用 ldapssl

						bindUserConnstate = LDAPUtil.user_sslconnect(baseDn, bindUser, bindPass, url);
						logger.info("//==========binduser ldap ssl connect state is :" + bindUserConnstate);
					} else {

						bindUserConnstate = LDAPUtil.user_connect(baseDn, bindUser, bindPass, url);
						logger.info("//==========binduser ldap connect state is :" + bindUserConnstate);
					}

					if (bindUserConnstate) { // ldap登录成功

						map = LDAPUtil.getUser(username, filter); // 获取用户的信息

						boolean userConnstate = false;

						if (map == null || map.isEmpty()) {
							logger.info("LDAPUtil.getUser is empty");
							insertfailurelog(username + "(未知)", clientInfo, "ldap用户不存在,登录失败", logsql, tx, address,
									LDAP_LOGIN);
							throw new LDAPNotSearchUserException();
						}

						String userdn = "";

						if (map.get("entryDN") != null) {

							userdn = (String) map.get("entryDN");
						}

						if (map.get("dn").toString() != null) {

							userdn = map.get("dn").toString();
						}

						if (map.get("distinguishedName") != null) {

							userdn = (String) map.get("distinguishedName");
						}

						if (userdn == null || "".equals(userdn)) { // 没有取到userdn

							logger.info("没有取到userdn");
							insertfailurelog(username + "(未知)", clientInfo, "ldap用户不存在,登录失败", logsql, tx, address,
									LDAP_LOGIN);
							throw new LDAPNotSearchUserException();
						}

						boolean ouFlag = false;

						if (userOu.contains("||")) {

							String[] userOus = userOu.split("\\|\\|");

							for (String ou : userOus) {

								ou = ou.toLowerCase().replaceAll(" ", "");

								if (userdn.toLowerCase().replaceAll(" ", "").contains(ou)) {
									ouFlag = true;
								}

							}

						} else {

							if (userdn.toLowerCase().replaceAll(" ", "").contains(userOu)) {
								ouFlag = true;
							}
						}

						if (!ouFlag) {

							logger.error("user 不在 userOu的范围内，禁止登录！  user:" + map.get("distinguishedName") + "; userOu:"
									+ userOu);
							insertfailurelog(username + "(未知)", clientInfo, "用户不在userOu的范围内", logsql, tx, address,
									LDAP_LOGIN);
							throw new LDAPOUMismatchingException();
						}

						if (flagssl) { // 启用 ldapssl

							userConnstate = LDAPUtil.user_sslconnect(baseDn, userdn, password, url);
							logger.info("//==========用户 ldap ssl connect state is :" + userConnstate);
						} else {

							userConnstate = LDAPUtil.user_connect(baseDn, userdn, password, url);
							logger.info("//==========用户 ldap connect state is :" + userConnstate);
						}

						if (userConnstate) { // 用户登录成功

							LDAPUtil.closeContext(); // 关闭连接

							// 查询用户
							String sqluser = "select * from ri_user where account = ?";

							Map<String, Object> mapuser = qr.query(sqluser, new MapHandler(), username);

							// 查询表字段
							String sqlcolumn = "select COLUMN_NAME from INFORMATION_SCHEMA.Columns where table_name='ri_user' and table_schema='bigops'";

							List<Object[]> list = qr.query(sqlcolumn, new ArrayListHandler());

							Map<String, Object> mapdb = new HashMap<>();

							for (int i = 0; i < list.size(); i++) { // 将映射mysql键值都存到List中
								for (String s : setmysql) {
									if (list.get(i)[0].equals(mapmysql.get(s))) {
										mapdb.put(String.valueOf(mapmysql.get(s)), map.get(s));
									}
								}
							}
							List<Object> params = new ArrayList<Object>();

							Set<String> setupdate = mapdb.keySet();

							List<MessageDescriptor> mlist = new ArrayList<>();

							if (mapuser != null && mapuser.size() > 0) { // 判断 用户在mysql中存在

								String sqlupdate = "update ri_user set ";

								for (String s : setupdate) {
									if (mapdb.get(s) != null && !"".equals(String.valueOf(mapdb.get(s)))) {
										sqlupdate = sqlupdate + s + "=?,";
										params.add(mapdb.get(s));
									}
								}

								sqlupdate = sqlupdate + "pass =? where account=?";
								params.add(pwd);
								params.add(username);

								if (qr.update(sqlupdate, params.toArray()) > 0) {

									if ("启用".equals((String) mapuser.get("sso_mfa"))) {
										String secretKey = (String) mapuser.get("mfa_secretkey");
										secretKey = AESUtil.decrypt(secretKey, Constants.MD5_SALT);
										if (StringUtils.isBlank(code)) {
											throw new DynamicCodeIsNullException();
										} else {
											if (StringUtils.isBlank(secretKey)) {
												throw new DynamicCodeExpiredException();
											} else if (!GoogleAuthenticatorUtils.verify(secretKey, code)) {
												throw new DynamicCodeExpiredException();
											} else {
												Map<String, Object> result = new HashMap<String, Object>();
												Set<String> sets = (Set<String>) mapuser.keySet();
												Set<String> principalAttributesets = PrincipalAttributeUtils
														.getPrincipalAttributes();
												boolean b = sets.containsAll(principalAttributesets);
												if (b) {
													for (String value : principalAttributesets) {
														result.put(value, mapuser.get(value));
													}
												} else {
													throw new RuntimeException("cas.principal.attributes 属性错误");
												}
												// 允许登录，并且通过this.principalFactory.createPrincipal 来返回用户属性
												List<MessageDescriptor> listMessageDescriptor = new ArrayList<>();
												return createHandlerResult(credential,
														this.principalFactory.createPrincipal(username, result),
														listMessageDescriptor);
											}
										}
									}

									insertldapSuccesslog(clientInfo, username, address, logsql, tx, LDAP_LOGIN,
											Constants.LOGIN_SUCCESS);
									return createHandlerResult(credential,
											this.principalFactory.createPrincipal(username, mapdb), mlist);
								}
							} else { // 不存在

								String sqlinsert = "insert into ri_user (";
								boolean accountExpiresFlag = true;
								for (String s : setupdate) {
									if (mapdb.get(s) != null && !"".equals(String.valueOf(mapdb.get(s)))) {
										sqlinsert = sqlinsert + s + ",";
										if ("etime".equals(s)) {
											params.add(Constants.FT
													.format(DateUtil.fromDnetToJdate(mapdb.get(s).toString())));
										} else {
											params.add(mapdb.get(s));
										}
									}
									if ("etime".equals(s)) {
										accountExpiresFlag = false;
									}
								}

								if (accountExpiresFlag) {
									sqlinsert = sqlinsert + "etime,";
									params.add(Constants.FT.parse("9999-01-01 01:01:01"));
								}
								sqlinsert = sqlinsert + "pass,type,status,sso_mfa) values (";
								for (String s : setupdate) {
									if (mapdb.get(s) != null && !"".equals(String.valueOf(mapdb.get(s)))) {
										sqlinsert = sqlinsert + "?,";
									}
								}
								if (accountExpiresFlag) {
									sqlinsert = sqlinsert + "?,";
								}

								sqlinsert = sqlinsert + "?,?,?,?)";
								params.add(pwd);
								params.add("员工");
								params.add("启用");
								params.add("禁用");

								if (qr.update(sqlinsert, params.toArray()) > 0) {

									insertldapSuccesslog(clientInfo, username, address, logsql, tx, LDAP_LOGIN,
											Constants.LOGIN_SUCCESS);
									logger.info("// ldap================" + username + JsonUtil.toJson(mapdb));
									return createHandlerResult(credential,
											this.principalFactory.createPrincipal(username, mapdb), mlist);
								}
							}

						} else {

							logger.info("//========user connect faild ");
							insertfailurelog(username, clientInfo, Constants.LOGIN_FAIL, logsql, tx, address,
									LDAP_LOGIN);
							throw new FailedLoginException();
						}

					} else {

						logger.info("//========admin connect faild ");
						insertfailurelog(username, clientInfo, Constants.LOGIN_FAIL, logsql, tx, address, LDAP_LOGIN);
						throw new FailedLoginException();
					}
				} catch (LDAPSearchMoreUserException e) {
					throw new LDAPSearchMoreUserException();
				} catch (SQLException e) {
					e.printStackTrace();
				} catch (ParseException e) {
					e.printStackTrace();
				}
			} else {

				throw new RuntimeException("未知的认证方式");
			}

			insertfailurelog(username, clientInfo, Constants.LOGIN_FAIL, logsql, tx, address, "未知");
			throw new FailedLoginException();
		}
	}

	public boolean supports(Credential credential) {
		return credential instanceof UsernamePassDynamicPassCredential;
	}

	public String getCityAddress(ClientInfo clientInfo, String address) {
		QQWry qqwry = null;
		try {
			qqwry = new QQWry();
		} catch (IOException e) {
			e.printStackTrace();
			return address;
		}

		final IPZone zone = qqwry.findIP(clientInfo.getClientIpAddress());
		return zone.getMainInfo();
	}

	public void insertfailurelog(String username, ClientInfo clientInfo, String content, String logsql,
			TxQueryRunner tx, String address, String loginType) {
		Date d = new Date();
		address = getCityAddress(clientInfo, address);
		Object[] logparams = new Object[] { username, Constants.LOGIN_STATUS_FAIL, Constants.LOGIN, content, loginType,
				clientInfo.getClientIpAddress(), address, d };
		try {
			tx.update(logsql, logparams);
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	public void insertldapSuccesslog(ClientInfo clientInfo, String username, String address, String logsql,
			TxQueryRunner tx, String loginType, String content) {
		Date d = new Date();
		Object[] logparams = new Object[] { username, Constants.LOGIN_STATUS_SUCCESS, Constants.LOGIN, content,
				loginType, clientInfo.getClientIpAddress(), address, d };

		try {
			int logresult = tx.update(logsql, logparams);
			if (logresult <= 0) {
				throw new RuntimeException("插入登录日志失败");
			}
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

}
