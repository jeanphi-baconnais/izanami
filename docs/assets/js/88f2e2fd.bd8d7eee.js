"use strict";(self.webpackChunkizanami_documentation=self.webpackChunkizanami_documentation||[]).push([[6922],{5264:(e,i,n)=>{n.r(i),n.d(i,{assets:()=>c,contentTitle:()=>a,default:()=>u,frontMatter:()=>r,metadata:()=>l,toc:()=>d});var s=n(5893),o=n(1151),t=n(3074);const r={title:"Webhooks"},a=void 0,l={id:"guides/webhooks",title:"Webhooks",description:"This page covers features available only from 2.3.0",source:"@site/docs/04-guides/12-webhooks.mdx",sourceDirName:"04-guides",slug:"/guides/webhooks",permalink:"/izanami/docs/guides/webhooks",draft:!1,unlisted:!1,tags:[],version:"current",sidebarPosition:12,frontMatter:{title:"Webhooks"},sidebar:"tutorialSidebar",previous:{title:"Configuring Izanami",permalink:"/izanami/docs/guides/configuration"},next:{title:"Clients",permalink:"/izanami/docs/clients/"}},c={},d=[{value:"Webhook creation",id:"webhook-creation",level:2},{value:"Webhook call",id:"webhook-call",level:2},{value:"Resilience",id:"resilience",level:2},{value:"Rights",id:"rights",level:2}];function h(e){const i={a:"a",code:"code",h2:"h2",li:"li",p:"p",table:"table",tbody:"tbody",td:"td",th:"th",thead:"thead",tr:"tr",ul:"ul",...(0,o.a)(),...e.components};return(0,s.jsxs)(s.Fragment,{children:[(0,s.jsx)(t.j,{children:"This page covers features available only from 2.3.0"}),"\n",(0,s.jsx)(i.p,{children:"Webhooks are a way to make Izanami call a provided URL when a feature is modified."}),"\n",(0,s.jsx)(i.p,{children:"Given URL is called with new activation status for modified feature."}),"\n",(0,s.jsx)(i.p,{children:"This is usefull if you want to execute some specific code on feature activation,\nor if you want to keep your feature in sync with an external system that can't perform HTTP\ncall to Izanami."}),"\n",(0,s.jsx)(i.h2,{id:"webhook-creation",children:"Webhook creation"}),"\n",(0,s.jsx)(i.p,{children:'To create a webhook, go to the webhook screen of your tenant and click the "Create new webhook" button.'}),"\n",(0,s.jsx)(i.p,{children:"This form allows to configure your webhook :"}),"\n",(0,s.jsxs)(i.ul,{children:["\n",(0,s.jsxs)(i.li,{children:[(0,s.jsx)(i.code,{children:"url"})," indicates which URL should be call when a feature is updated"]}),"\n",(0,s.jsxs)(i.li,{children:[(0,s.jsx)(i.code,{children:"name"})," and ",(0,s.jsx)(i.code,{children:"description"})," allows to describe what your hook is and what it's doing"]}),"\n",(0,s.jsxs)(i.li,{children:[(0,s.jsx)(i.code,{children:"headers"})," allows to specify custom HTTPs headers to pass while calling webhook"]}),"\n",(0,s.jsxs)(i.li,{children:[(0,s.jsx)(i.code,{children:"features"})," and ",(0,s.jsx)(i.code,{children:"projects"})," specify hook scope : hook will be called only when a liste feature (or a feature of one of the listed projects) is modified"]}),"\n",(0,s.jsxs)(i.li,{children:[(0,s.jsx)(i.code,{children:"context"})," and ",(0,s.jsx)(i.code,{children:"user"})," allows to specify context and user for which activation will be recomputed"]}),"\n",(0,s.jsxs)(i.li,{children:[(0,s.jsx)(i.code,{children:"custom body"})," allows to transform sent body (using a ",(0,s.jsx)(i.a,{href:"https://handlebarsjs.com/",children:"handlebars template"}),").\nThis is espacially usefull when integrating with external services, such as messaging systems."]}),"\n"]}),"\n",(0,s.jsx)(i.h2,{id:"webhook-call",children:"Webhook call"}),"\n",(0,s.jsx)(i.p,{children:"Webhook is called :"}),"\n",(0,s.jsxs)(i.ul,{children:["\n",(0,s.jsx)(i.li,{children:"When feature is created under given scope"}),"\n",(0,s.jsx)(i.li,{children:"When feature concerned by given scope is deleted"}),"\n",(0,s.jsx)(i.li,{children:"When enabling of feature concerned by given scope is modified"}),"\n",(0,s.jsx)(i.li,{children:"When activation conditions of feature concerned by given scope are modified"}),"\n",(0,s.jsx)(i.li,{children:"When an overload is created / updated / deleted for a given feature"}),"\n"]}),"\n",(0,s.jsx)(i.h2,{id:"resilience",children:"Resilience"}),"\n",(0,s.jsx)(i.p,{children:"Izanami offers some guaranties for webhook resilience."}),"\n",(0,s.jsxs)(i.p,{children:["If webhook call timeout or if remote server respond with an error code,\nIzanami will retry the call several times with an increased interval between calls.\nTo do so, Izanami will use ",(0,s.jsx)(i.a,{href:"https://en.wikipedia.org/wiki/Exponential_backoff",children:"exponential backoff algorithm"}),"\nelements of this algorithm can be configured, see ",(0,s.jsx)(i.a,{href:"./configuration#webhooks",children:"dedicated configuration section"})," for details."]}),"\n",(0,s.jsx)(i.p,{children:"If Izanami instance is shut down (or crashes) while making initial or retry call, webhook call will be retried after some times either when Izanami instances restart or by another instance if Izanami has multiple instances."}),"\n",(0,s.jsx)(i.h2,{id:"rights",children:"Rights"}),"\n",(0,s.jsx)(i.p,{children:"Each hook has a dedicated right section, as for keys, tenants or projects, users can have 3 right levels on hooks :"}),"\n",(0,s.jsxs)(i.table,{children:[(0,s.jsx)(i.thead,{children:(0,s.jsxs)(i.tr,{children:[(0,s.jsx)(i.th,{children:"Read hook information"}),(0,s.jsx)(i.th,{children:"Read hook information"}),(0,s.jsx)(i.th,{children:"Create/update webhook"}),(0,s.jsx)(i.th,{children:"Delete webhook"})]})}),(0,s.jsxs)(i.tbody,{children:[(0,s.jsxs)(i.tr,{children:[(0,s.jsx)(i.td,{children:"Read"}),(0,s.jsx)(i.td,{children:"\u2705"}),(0,s.jsx)(i.td,{children:"\u274c"}),(0,s.jsx)(i.td,{children:"\u274c"})]}),(0,s.jsxs)(i.tr,{children:[(0,s.jsx)(i.td,{children:"Write"}),(0,s.jsx)(i.td,{children:"\u2705"}),(0,s.jsx)(i.td,{children:"\u2705"}),(0,s.jsx)(i.td,{children:"\u274c"})]}),(0,s.jsxs)(i.tr,{children:[(0,s.jsx)(i.td,{children:"Admin (or tenant/global admin)"}),(0,s.jsx)(i.td,{children:"\u2705"}),(0,s.jsx)(i.td,{children:"\u2705"}),(0,s.jsx)(i.td,{children:"\u2705"})]})]})]})]})}function u(e={}){const{wrapper:i}={...(0,o.a)(),...e.components};return i?(0,s.jsx)(i,{...e,children:(0,s.jsx)(h,{...e})}):h(e)}},3074:(e,i,n)=>{n.d(i,{j:()=>r});const s={description__trivia:"description__trivia_yesz"},o=n.p+"assets/images/izanami-fcff3cbcd789d673683f3365a3ddf9e4.png";var t=n(5893);function r(e){let{children:i}=e;return(0,t.jsxs)("div",{className:s.description__trivia,children:[(0,t.jsx)("img",{src:o}),(0,t.jsx)("div",{children:i})]})}},1151:(e,i,n)=>{n.d(i,{Z:()=>a,a:()=>r});var s=n(7294);const o={},t=s.createContext(o);function r(e){const i=s.useContext(t);return s.useMemo((function(){return"function"==typeof e?e(i):{...i,...e}}),[i,e])}function a(e){let i;return i=e.disableParentContext?"function"==typeof e.components?e.components(o):e.components||o:r(e.components),s.createElement(t.Provider,{value:i},e.children)}}}]);