package org.hyperic.plugin.vrealize.automation;

import static com.vmware.hyperic.model.relations.RelationType.PARENT;
import static org.hyperic.plugin.vrealize.automation.VRAUtils.configFile;
import static org.hyperic.plugin.vrealize.automation.VRAUtils.getParameterizedName;
import static org.hyperic.plugin.vrealize.automation.VraConstants.CREATE_IF_NOT_EXIST;
import static org.hyperic.plugin.vrealize.automation.VraConstants.KEY_APPLICATION_NAME;
import static org.hyperic.plugin.vrealize.automation.VraConstants.TYPE_VCO_TAG;
import static org.hyperic.plugin.vrealize.automation.VraConstants.TYPE_VRA_APPLICATION;
import static org.hyperic.plugin.vrealize.automation.VraConstants.TYPE_VRA_VCO_LOAD_BALANCER;

import java.util.Collection;
import java.util.List;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperic.hq.product.PluginException;
import org.hyperic.hq.product.ServerResource;
import org.hyperic.util.config.ConfigResponse;

import com.vmware.hyperic.model.relations.ObjectFactory;
import com.vmware.hyperic.model.relations.RelationType;
import com.vmware.hyperic.model.relations.Resource;
import com.vmware.hyperic.model.relations.ResourceSubType;
import com.vmware.hyperic.model.relations.ResourceTier;

/**
 *
 * @author imakhlin
 */
public class DiscoveryVCOAppServer extends Discovery {

    private static final Log log = LogFactory.getLog(DiscoveryVCOAppServer.class);

    @Override
    public List<ServerResource> getServerResources(ConfigResponse platformConfig)
        throws PluginException {
        log.debug("[getServerResources] platformConfig=" + platformConfig);
        String platformFqdn = platformConfig.getValue("platform.fqdn");
        log.debug("[getServerResources] platformFqdn=" + platformFqdn);

        List<ServerResource> servers = super.getServerResources(platformConfig);

        for (ServerResource server : servers) {
            String srvName = server.getName();
            String srvType = server.getType();
            log.debug("[getServerResources] vCO server=" + srvName + " vCO Type=" + srvType);

            Properties cfg = configFile("/etc/vco/app-server/vmo.properties");
            String jdbcURL = cfg.getProperty("database.url", "").replaceAll("\\:", ":");

            log.debug("[getServerResources] jdbcURL='" + jdbcURL + "'");
            /*
            List<String> jdbcInfo = Arrays.asList(jdbcURL.split(":"));
            if (jdbcInfo.contains("sqlserver")) {
            } else if (jdbcInfo.contains("postgresql")) {
            }
            */

            final Collection<String> vcoLoadBalancerFqdns = getVcoLoadBalancerFqdns(platformFqdn);
            Resource modelResource = getCommonModel(srvName, srvType, vcoLoadBalancerFqdns);
            String modelXml = VRAUtils.marshallResource(modelResource);
            VRAUtils.setModelProperty(server, modelXml);
        }
        return servers;
    }

    /**
     * @return
     */
    public Collection<String> getVcoLoadBalancerFqdns(String vcoServerName) {
        return VRAUtils.getDnsNames(String.format("https://%s:8281", vcoServerName));
    }

    private static Resource getCommonModel(String serverName,
                                           String serverType,
                                           Collection<String> loadBalancerFqdns) {
        ObjectFactory factory = new ObjectFactory();

        Resource vcoServer =
                    factory.createResource(false, serverType, serverName, ResourceTier.SERVER);

        // TODO: remove it, once you find a way to correlate between vCO Load Balancer and vCO server
        // vcoServer.addIdentifiers(factory.createIdentifier(KEY_VCO_SERVER_FQDN, serverName));

        Resource serverGroup =
                    factory.createResource(CREATE_IF_NOT_EXIST, TYPE_VCO_TAG,
                                getParameterizedName(KEY_APPLICATION_NAME, TYPE_VCO_TAG),
                                ResourceTier.LOGICAL,
                                ResourceSubType.TAG);

        Resource vraApp =
                    factory.createResource(!CREATE_IF_NOT_EXIST, TYPE_VRA_APPLICATION,
                                getParameterizedName(KEY_APPLICATION_NAME, TYPE_VRA_APPLICATION), ResourceTier.LOGICAL,
                                ResourceSubType.TAG);

        Resource topLoadBalancerTag =
                    VRAUtils.createLogialResource(factory, VraConstants.TYPE_LOAD_BALANCER_TAG,
                                VRAUtils.getParameterizedName(KEY_APPLICATION_NAME));
        topLoadBalancerTag.addRelations(factory.createRelation(vraApp, PARENT));

        Resource vcoLoadBalancerTag =
                    VRAUtils.createLogialResource(factory, VraConstants.TYPE_VRA_VCO_LOAD_BALANCER_TAG,
                                VRAUtils.getParameterizedName(KEY_APPLICATION_NAME));
        vcoLoadBalancerTag.addRelations(factory.createRelation(topLoadBalancerTag, PARENT, CREATE_IF_NOT_EXIST));

        if (loadBalancerFqdns != null && loadBalancerFqdns.size() > 0) {
            for (String loadBalancerFqdn : loadBalancerFqdns) {
                Resource vcoLoadBalancer = factory.createResource(!CREATE_IF_NOT_EXIST, TYPE_VRA_VCO_LOAD_BALANCER,
                            VRAUtils.getFullResourceName(loadBalancerFqdn, TYPE_VRA_VCO_LOAD_BALANCER),
                            ResourceTier.SERVER);

                vcoLoadBalancer.addRelations(factory.createRelation(vcoLoadBalancerTag, PARENT, CREATE_IF_NOT_EXIST));

                vcoServer.addRelations(factory.createRelation(vcoLoadBalancer, RelationType.SIBLING));
            }
        } else {
            log.warn("Unable to get the VCO load balancer FQDN name");
        }

        vcoServer.addRelations(factory.createRelation(serverGroup, RelationType.PARENT));

        return vcoServer;
    }
}
