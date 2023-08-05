package com.tencent.supersonic.chat.service.impl;

import com.google.common.collect.Lists;
import com.tencent.supersonic.auth.api.authentication.pojo.User;
import com.tencent.supersonic.chat.api.component.SemanticLayer;
import com.tencent.supersonic.chat.plugin.PluginParseConfig;
import com.tencent.supersonic.chat.plugin.Plugin;
import com.tencent.supersonic.chat.api.pojo.request.PluginQueryReq;
import com.tencent.supersonic.chat.persistence.dataobject.PluginDO;
import com.tencent.supersonic.chat.persistence.dataobject.PluginDOExample;
import com.tencent.supersonic.chat.persistence.repository.PluginRepository;
import com.tencent.supersonic.chat.parser.ParseMode;
import com.tencent.supersonic.chat.plugin.event.PluginAddEvent;
import com.tencent.supersonic.chat.plugin.event.PluginDelEvent;
import com.tencent.supersonic.chat.plugin.event.PluginUpdateEvent;
import com.tencent.supersonic.chat.service.PluginService;
import com.tencent.supersonic.chat.utils.ComponentFactory;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.semantic.api.model.response.DomainResp;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class PluginServiceImpl implements PluginService {

    private PluginRepository pluginRepository;

    private ApplicationEventPublisher publisher;

    public PluginServiceImpl(PluginRepository pluginRepository,
                             ApplicationEventPublisher publisher) {
        this.pluginRepository = pluginRepository;
        this.publisher = publisher;
    }

    @Override
    public synchronized void createPlugin(Plugin plugin, User user){
        PluginDO pluginDO = convert(plugin, user);
        pluginRepository.createPlugin(pluginDO);
        //compatible with H2 db
        List<Plugin> plugins = getPluginList();
        publisher.publishEvent(new PluginAddEvent(this, plugins.get(plugins.size()-1)));
    }

    @Override
    public void updatePlugin(Plugin plugin, User user){
        Long id = plugin.getId();
        PluginDO pluginDO = pluginRepository.getPlugin(id);
        Plugin oldPlugin = convert(pluginDO);
        convert(plugin, pluginDO, user);
        pluginRepository.updatePlugin(pluginDO);
        publisher.publishEvent(new PluginUpdateEvent(this, oldPlugin, plugin));
    }

    @Override
    public void deletePlugin(Long id){
        PluginDO pluginDO = pluginRepository.getPlugin(id);
        if (pluginDO != null) {
            pluginRepository.deletePlugin(id);
            publisher.publishEvent(new PluginDelEvent(this, convert(pluginDO)));
        }
    }


    @Override
    public List<Plugin> getPluginList() {
        List<Plugin> plugins = Lists.newArrayList();
        List<PluginDO> pluginDOS = pluginRepository.getPlugins();
        if(CollectionUtils.isEmpty(pluginDOS)){
            return plugins;
        }
        return pluginDOS.stream().map(this::convert).collect(Collectors.toList());
    }

    @Override
    public List<Plugin> fetchPluginDOs(String queryText, String type) {
        List<PluginDO> pluginDOS = pluginRepository.fetchPluginDOs(queryText,type);
        return convertList(pluginDOS);
    }


    @Override
    public List<Plugin> query(PluginQueryReq pluginQueryReq) {
        PluginDOExample pluginDOExample = new PluginDOExample();
        pluginDOExample.createCriteria();
        if (StringUtils.isNotBlank(pluginQueryReq.getType())) {
            pluginDOExample.getOredCriteria().get(0).andTypeEqualTo(pluginQueryReq.getType());
        }
        if (StringUtils.isNotBlank(pluginQueryReq.getDomain())) {
            pluginDOExample.getOredCriteria().get(0).andDomainLike('%' + pluginQueryReq.getDomain() + '%');
        }
        if (StringUtils.isNotBlank(pluginQueryReq.getParseMode())) {
            pluginDOExample.getOredCriteria().get(0).andParseModeEqualTo(pluginQueryReq.getParseMode());
        }
        if (StringUtils.isNotBlank(pluginQueryReq.getName())) {
            pluginDOExample.getOredCriteria().get(0).andNameLike('%' + pluginQueryReq.getName() + '%');
        }
        if (StringUtils.isNotBlank(pluginQueryReq.getPattern())) {
            pluginDOExample.getOredCriteria().get(0).andPatternLike('%' + pluginQueryReq.getPattern() + '%');
        }
        if (StringUtils.isNotBlank(pluginQueryReq.getCreatedBy())) {
            pluginDOExample.getOredCriteria().get(0).andCreatedByEqualTo(pluginQueryReq.getCreatedBy());
        }
        List<PluginDO> pluginDOS = pluginRepository.query(pluginDOExample);
        if (StringUtils.isNotBlank(pluginQueryReq.getPattern())) {
            pluginDOS = pluginDOS.stream().filter(pluginDO ->
                    pluginDO.getPattern().contains(pluginQueryReq.getPattern()) ||
                            (pluginDO.getName()!=null && pluginDO.getName().contains(pluginQueryReq.getPattern())))
                    .collect(Collectors.toList());
        }
        return convertList(pluginDOS);
    }

    @Override
    public Optional<Plugin> getPluginByName(String name) {
        log.info("name:{}", name);
        return getPluginList().stream()
                .filter(plugin -> {
                    if (StringUtils.isBlank(plugin.getParseModeConfig())) {
                        return false;
                    }
                    PluginParseConfig functionCallConfig = JsonUtil.toObject(plugin.getParseModeConfig(), PluginParseConfig.class);
                    if (Objects.isNull(functionCallConfig)) {
                        return false;
                    }
                    return functionCallConfig.getName().equalsIgnoreCase(name);
                })
                .findFirst();
    }

    @Override
    public List<Plugin> queryWithAuthCheck(PluginQueryReq pluginQueryReq) {
        return authCheck(query(pluginQueryReq));
    }

    private List<Plugin> authCheck(List<Plugin> plugins) {
        SemanticLayer semanticLayer = ComponentFactory.getSemanticLayer();
        List<Long> domainIdAuthorized = semanticLayer.getDomainListForAdmin().stream()
                .map(DomainResp::getId).collect(Collectors.toList());
        plugins = plugins.stream().filter(plugin -> {
            if (CollectionUtils.isEmpty(plugin.getDomainList()) || plugin.isContainsAllDomain()) {
                return true;
            }
            for (Long domainId : plugin.getDomainList()) {
                if (domainIdAuthorized.contains(domainId)) {
                    return true;
                }
            }
            return false;
        }).collect(Collectors.toList());
        return plugins;
    }

    public Plugin convert(PluginDO pluginDO){
        Plugin plugin = new Plugin();
        BeanUtils.copyProperties(pluginDO,plugin);
        if (StringUtils.isNotBlank(pluginDO.getDomain())) {
            plugin.setDomainList(Arrays.stream(pluginDO.getDomain().split(","))
                    .map(Long::parseLong).collect(Collectors.toList()));
        }
        return plugin;
    }

    public PluginDO convert(Plugin plugin, User user){
        PluginDO pluginDO = new PluginDO();
        BeanUtils.copyProperties(plugin,pluginDO);
        pluginDO.setCreatedAt(new Date());
        pluginDO.setCreatedBy(user.getName());
        pluginDO.setUpdatedAt(new Date());
        pluginDO.setUpdatedBy(user.getName());
        pluginDO.setDomain(StringUtils.join(plugin.getDomainList(), ","));
        return pluginDO;
    }

    public PluginDO convert(Plugin plugin, PluginDO pluginDO, User user){
        BeanUtils.copyProperties(plugin,pluginDO);
        pluginDO.setUpdatedAt(new Date());
        pluginDO.setUpdatedBy(user.getName());
        pluginDO.setDomain(StringUtils.join(plugin.getDomainList(), ","));
        return pluginDO;
    }

    public List<Plugin> convertList(List<PluginDO> pluginDOS){
        if(!CollectionUtils.isEmpty(pluginDOS)){
            return pluginDOS.stream().map(this::convert).collect(Collectors.toList());
        }
        return Lists.newArrayList();
    }

}