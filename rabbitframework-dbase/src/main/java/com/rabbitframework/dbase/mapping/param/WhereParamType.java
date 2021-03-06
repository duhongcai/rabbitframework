package com.rabbitframework.dbase.mapping.param;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库参数组合实例类 提供对数据库条件解析的接口 oredCriteria条件集体合，一般情况下使用createCriteria()方法
 * 特殊情况下，调用createCriteria(criteria)或addCreateCriteria()方法
 * 
 * 
 */
public class WhereParamType {

	protected List<Criteria> oredCriteria;
	protected Map<String, Object> params;

	public WhereParamType() {
		oredCriteria = new ArrayList<Criteria>();
		params = new HashMap<String, Object>();
	}

	public List<Criteria> getOredCriteria() {
		return oredCriteria;
	}

	public Map<String, Object> getParams() {
		return params;
	}

	public void setParams(Map<String, Object> params) {
		if (params != null && params.size() > 0) {
			this.params.putAll(params);
		}
	}

	public void put(String key, String value) {
		params.put(key, value);
	}

	/**
	 * 根据参数，增加条件集体合
	 * 
	 * @param criteria
	 */
	public void createCriteria(Criteria criteria) {
		oredCriteria.add(criteria);
	}

	/**
	 * 实例化条件类，增加条件集合
	 * 
	 * @return
	 */
	public Criteria addCreateCriteria() {
		Criteria criteria = createCriteriaInternal();
		oredCriteria.add(criteria);
		return criteria;
	}

	/**
	 * 限定条件集体合，size==1
	 * 
	 * @return
	 */
	public Criteria createCriteria() {
		Criteria criteria = createCriteriaInternal();
		if (oredCriteria.size() == 0) {
			oredCriteria.add(criteria);
		}
		return criteria;
	}

	protected Criteria createCriteriaInternal() {
		Criteria criteria = new Criteria();
		return criteria;
	}

	public void clear() {
		oredCriteria.clear();
	}

}
