"""Build a configuration-ready SAP iFlow skeleton; adapters are added in the tenant."""
from pathlib import Path
import xml.etree.ElementTree as E
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parent
NS = {'bpmn2':'http://www.omg.org/spec/BPMN/20100524/MODEL', 'ifl':'http:///com.sap.ifl.model/Ifl.xsd', 'bpmndi':'http://www.omg.org/spec/BPMN/20100524/DI', 'dc':'http://www.omg.org/spec/DD/20100524/DC', 'di':'http://www.omg.org/spec/DD/20100524/DI'}
for p,u in NS.items(): E.register_namespace(p,u)
def node(parent, tag, attrs=None, text=None):
    prefix,local=tag.split(':'); el=E.SubElement(parent,'{'+NS[prefix]+'}'+local,attrs or {})
    el.text=text; return el
def props(parent, values):
    ext=node(parent,'bpmn2:extensionElements')
    for k,v in values.items():
        p=node(ext,'ifl:property'); E.SubElement(p,'key').text=k; E.SubElement(p,'value').text=v

root=E.Element('{'+NS['bpmn2']+'}definitions',{'id':'Definitions_1'})
collab=node(root,'bpmn2:collaboration',{'id':'Collaboration_1','name':'Default Collaboration'})
props(collab,{'namespaceMapping':'','httpSessionHandling':'None','returnExceptionToSender':'false','log':'All events','componentVersion':'1.2','allowedHeaderList':'Date|Subject|From|Message-ID','cmdVariantUri':'ctype::IFlowVariant/cname::IFlowConfiguration/version::1.2.4'})
for id,name,kind in [('Sender','Mailbox - add Mail IMAP adapter','EndpointSender'),('Receiver','Datasphere - add JDBC adapter','EndpointReceiver')]:
    p=node(collab,'bpmn2:participant',{'id':id,'name':name,'{'+NS['ifl']+'}type':kind})
    props(p,{'ifl:type':kind})
node(collab,'bpmn2:participant',{'id':'Participant_Process','name':'Cronacle email to JDBC','processRef':'Process_1','{'+NS['ifl']+'}type':'IntegrationProcess'})
process=node(root,'bpmn2:process',{'id':'Process_1','name':'Cronacle email to JDBC'})
props(process,{'transactionTimeout':'30','componentVersion':'1.2','transactionalHandling':'Not Required','cmdVariantUri':'ctype::FlowElementVariant/cname::IntegrationProcess/version::1.2.1'})
start=node(process,'bpmn2:startEvent',{'id':'Start','name':'Email'})
props(start,{'componentVersion':'1.0','cmdVariantUri':'ctype::FlowstepVariant/cname::MessageStartEvent/version::1.0'})
node(start,'bpmn2:outgoing',text='Flow_1'); node(start,'bpmn2:messageEventDefinition')
for i,(id,name,script) in enumerate([('Configure','Mapping settings','ConfigureMapping.groovy'),('Extract','Extract alert and error log','ExtractCronacleAlert.groovy'),('MapJdbc','Build HANA JDBC payload','BuildJdbcPayload.groovy')],1):
    step=node(process,'bpmn2:callActivity',{'id':id,'name':name})
    props(step,{'activityType':'Script','script':script,'scriptFunction':'processData','scriptType':'Groovy','scriptBundleId':'','componentVersion':'1.1','cmdVariantUri':'ctype::FlowstepVariant/cname::GroovyScript/version::1.1'})
    node(step,'bpmn2:incoming',text='Flow_'+str(i)); node(step,'bpmn2:outgoing',text='Flow_'+str(i+1))
end=node(process,'bpmn2:endEvent',{'id':'End','name':'Write to Datasphere'})
props(end,{'componentVersion':'1.1','cmdVariantUri':'ctype::FlowstepVariant/cname::MessageEndEvent/version::1.1.0'})
node(end,'bpmn2:incoming',text='Flow_4'); node(end,'bpmn2:messageEventDefinition')
for i,(a,b) in enumerate([('Start','Configure'),('Configure','Extract'),('Extract','MapJdbc'),('MapJdbc','End')],1): node(process,'bpmn2:sequenceFlow',{'id':'Flow_'+str(i),'sourceRef':a,'targetRef':b})
diagram=node(root,'bpmndi:BPMNDiagram',{'id':'Diagram_1'})
plane=node(diagram,'bpmndi:BPMNPlane',{'id':'Plane_1','bpmnElement':'Collaboration_1'})
for id,x,y,w,h in [('Sender',40,100,110,140),('Participant_Process',210,60,850,230),('Receiver',1120,100,110,140),('Start',245,145,32,32),('Configure',320,131,130,60),('Extract',500,131,145,60),('MapJdbc',705,131,145,60),('End',960,145,32,32)]:
    s=node(plane,'bpmndi:BPMNShape',{'id':'Shape_'+id,'bpmnElement':id}); node(s,'dc:Bounds',dict(x=str(x),y=str(y),width=str(w),height=str(h)))
for i,(x1,x2) in enumerate([(277,320),(450,500),(645,705),(850,960)],1):
    e=node(plane,'bpmndi:BPMNEdge',{'id':'Edge_'+str(i),'bpmnElement':'Flow_'+str(i)})
    for x in (x1,x2): node(e,'di:waypoint',{'x':str(x),'y':'161'})
path=ROOT/'src/main/resources/scenarioflows/integrationflow/EmailToDatasphere.iflw'
path.parent.mkdir(parents=True,exist_ok=True)
E.ElementTree(root).write(path,encoding='UTF-8',xml_declaration=True)
manifest=ROOT/'META-INF/MANIFEST.MF'; manifest.parent.mkdir(exist_ok=True)
manifest.write_text('Manifest-Version: 1.0\nBundle-ManifestVersion: 2\nSAP-BundleType: IntegrationFlow\nSAP-NodeType: IFLMAP\nSAP-RuntimeProfile: iflmap\nBundle-Name: EmailToDatasphere\nBundle-SymbolicName: EmailToDatasphere; singleton:=true\nBundle-Version: 1.0.0\n\n',encoding='utf-8')
(ROOT/'.project').write_text('<?xml version="1.0" encoding="UTF-8"?><projectDescription><name>EmailToDatasphere</name><comment/><projects/><buildSpec/><natures><nature>com.sap.ifl.projects.project</nature></natures></projectDescription>',encoding='utf-8')
dist=ROOT/'dist'; dist.mkdir(exist_ok=True)
with ZipFile(dist/'EmailToDatasphere.iflow.zip','w',ZIP_DEFLATED) as z:
    for p in [ROOT/'.project',manifest,*sorted((ROOT/'src').rglob('*'))]:
        if p.is_file(): z.write(p,p.relative_to(ROOT).as_posix())
print('Built dist/EmailToDatasphere.iflow.zip (adapter configuration required; tenant import unverified)')
