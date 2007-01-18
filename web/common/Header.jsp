<%@ page language="java" %>
<%@ page errorPage="/common/Error.jsp" %>
<%@ taglib uri="struts-html-el" prefix="html" %>
<%@ taglib uri="struts-tiles" prefix="tiles" %>
<%@ taglib uri="jstl-fmt" prefix="fmt" %>
<%@ taglib uri="jstl-c" prefix="c" %>
<%@ taglib uri="hq" prefix="hq" %>
<%--
  NOTE: This copyright does *not* cover user programs that use HQ
  program services by normal system calls through the application
  program interfaces provided as part of the Hyperic Plug-in Development
  Kit or the Hyperic Client Development Kit - this is merely considered
  normal use of the program, and does *not* fall under the heading of
  "derived work".
  
  Copyright (C) [2004, 2005, 2006], Hyperic, Inc.
  This file is part of HQ.
  
  HQ is free software; you can redistribute it and/or modify
  it under the terms version 2 of the GNU General Public License as
  published by the Free Software Foundation. This program is distributed
  in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
  even the implied warranty of MERCHANTABILITY or FITNESS FOR A
  PARTICULAR PURPOSE. See the GNU General Public License for more
  details.
  
  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
  USA.
 --%>

<script language="JavaScript" type="text/javascript">
    var genHelp = "<hq:help context="false"/>";
</script>
<script language="JavaScript" type="text/javascript">
  var help = "<hq:help/>";
</script>

<script src="<html:rewrite page="/js/rico.js"/>" type="text/javascript"></script>
<script src="<html:rewrite page="/js/"/>diagram.js" type="text/javascript"></script>

<table width="100%" border="0" cellspacing="0" cellpadding="0" style="border-top:2px solid #3399ff;border-bottom:2px solid #3399ff;" height="56">

<tr>
<td rowspan="2" width="34%">
    <table width="100%" border="0" cellspacing="0" cellpadding="0">
        <tr>
            <td class="logo">
                <html:link page="/Dashboard.do">
                    <c:choose>
                        <c:when test="${applicationScope.largeLogo}">
                            <html:img page="/customer/${applicationScope.largeLogoName}" width="225" height="31" alt=""
                                      border="0"/>
                            <br>
                            <html:img page="/images/cobrand_logo.gif" width="225" height="25" alt="" border="0"/>
                        </c:when>
                        <c:otherwise>
                            <html:img page="/images/logo_large2.gif" width="223" height="54" alt="" border="0"/>
                        </c:otherwise>
                    </c:choose>
                </html:link>
            </td>
            <td width="100%" class="logo">
                <html:img page="/images/spacer.gif" width="1" height="1" alt="" border="0"/>
            </td>
            <td class="logo">
                <html:img page="/images/logo_Image_large2.jpg" width="225" height="54" alt="" border="0"/>
            </td>
        </tr>
    </table>
</td>
<td valign="top" width="66%">
    <table border="0" cellpadding="0" cellspacing="0" height="54" width="100%">
        <tr>
            <td class="MastheadBgTop" colspan="5" height="23" nowrap>
                <table border="0" cellspacing="0" cellpadding="0">
                    <tr>

                        <td class="MastheadContent" nowrap style="font-weight:bold;padding-right:35px;padding-left:9px;">
                            <c:out value="${sessionScope.webUser.username}"/>
                            &nbsp;-&nbsp;
                            <html:link page="/Logout.do">Logout</html:link>
                        </td>
                        <td height="18" class="MastheadBgTop" nowrap>
                        <table border="0" cellspacing="0" cellpadding="0" height="18">
                           <tr>

                        <td class="MastheadContent" nowrap style="font-weight:bold;padding-left:9px;">
                            <fmt:message key="header.RecentAlerts"/>:
                        </td>
                        <td nowrap style="font-weight:bold;">
                            <script language="JavaScript1.2">
      <!--
      var refreshCount = 0;
      var autoLogout = true;

      function refreshAlerts() {
        refreshCount++;

        if (refreshCount < 30) {
          setTimeout( "refreshAlerts()", 60*1000 );
        } else if (autoLogout) {
          top.location.href = "<html:rewrite action="/Logout"/>";
        }

        new Ajax.Request('<html:rewrite page="/common/RecentAlerts.jsp"/>',
                         {method: 'get', onSuccess:showRecentAlertResponse,
                                         onFailure :reportError});
      }

      function showRecentAlertResponse(originalRequest) {
        $('recentAlerts').innerHTML = originalRequest.responseText;
      }

      onloads.push( refreshAlerts );
      //-->
      </script>
                            <div id="recentAlerts" style="font-weight:bold;"></div>
                        </td>
                       </tr>
                    </table>
                </td>
                <div style="display:none;float:right;margin-right:15px;" id="loading">
		        <html:img page="/images/ajax-loader.gif" border="0" width="16" height="16" /></div>
		       
          </tr>
         </table>
       </td>
    </tr>
       
        <tr>
            <td class="MastheadBgBottom" style="padding-left:10px;padding-top:2px" align="left" height="23" colspan="5">
                <table border="0" cellspacing="0" cellpadding="0">
                    <tr>
                        <td style="border-right:2px solid #ffffff" class="mainNavText" nowrap>
                            <html:link page="/Dashboard.do"><span
                                    style="padding-right:10px;">Dashboard</span></html:link>
                        </td>
                        <td style="border-right:2px solid #ffffff" class="mainNavText" nowrap>
                            <html:link page="/ResourceHub.do"><span style="padding-right:10px;padding-left:10px;">Browse Resources</span></html:link>
                        </td>
                        <td style="border-right:2px solid #ffffff" class="mainNavText" nowrap>
                            <html:link page="/Admin.do"><span style="padding-right:10px;padding-left:10px;">Administration</span></html:link>
                        </td>
                        <td style="border-right:2px solid #ffffff" class="mainNavText"><a href="." onclick="toggleMenu('recent');return false;"><span
                                id="recentImg" style="padding-right:10px;padding-left:10px;">Recently Viewed Resources</span></a>
                            <div style="clear: all;"></div>

                            <div id="recent" style="position:absolute; z-index: 300; margin-top: 2px;display:none;">
                            <tiles:insert definition=".toolbar.recentResources"/>
                            </div>

                        </td>
                        <td class="mainNavText" nowrap>
                            <html:link href=""
                                       onclick="window.open(genHelp,'help','width=800,height=650,scrollbars=yes,toolbar=yes,left=80,top=80,resizable=yes'); return false;">
                                <span style="padding-right:10px;padding-left:10px;">Help</span></html:link>
                        </td>
                    </tr>

                </table>
            </td>
        </tr>
    </table>
    </td>
</tr>

</table>
<script language="JavaScript1.2">
      <!--
      var refreshCount = 0;
      var autoLogout = true;
                                                                                    
      function refreshAlerts() {
        refreshCount++;

        if (refreshCount < 30) {
          setTimeout( "refreshAlerts()", 60*1000 );
        } else if (autoLogout) {
          top.location.href = "<html:rewrite action="/Logout"/>";
        }

        new Ajax.Request('<html:rewrite page="/common/RecentAlerts.jsp"/>',
                         {method: 'get', onSuccess:showRecentAlertResponse,
                                         onFailure :reportError});
      }

      function showRecentAlertResponse(originalRequest) {
        $('recentAlerts').innerHTML = originalRequest.responseText;
      }

      onloads.push( refreshAlerts );
      //-->
      </script>