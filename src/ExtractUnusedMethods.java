import java.io.*;
import java.util.*;

import com.sforce.async.*;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.soap.tooling.*;
import com.sforce.soap.tooling.sobject.MetadataContainer;
import com.sforce.soap.tooling.sobject.SObject;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;
import com.sforce.soap.partner.LoginResult;
import com.sforce.soap.tooling.sobject.*;

public class ExtractUnusedMethods {

    static ToolingConnection toolingConnection;

    public static void main(String[] args) throws AsyncApiException, ConnectionException, IOException {
        ExtractUnusedMethods example = new ExtractUnusedMethods();
        // Replace arguments below with your credentials and test file name
        // The first parameter indicates that we are loading Account records
        example.getUnusedApexMethods();
        List<SObject> oApexClassesList = new ArrayList<SObject>();
        //SObject[] oApexClasses = toolingConnection.queryAll("select Id, Name, Body from ApexClass where NamespacePrefix = null and name in ('superSort','TrialCustomerPortalHomePageController','superSortTest')").getRecords();
        QueryResult qr = toolingConnection.queryAll("select Id, Name, Body from ApexClass where NamespacePrefix = null and (not name like '%test%') and (not name like '%Controller%') and (not name like '%Tooling%')  and (not name like '%Mock%') and (not name like '%crmasiaDaimler%')");
        boolean done = false;
        if (qr.getSize() > 0) {
            System.out.println("Logged-in user can see a total of " + qr.getSize());
            while (!done) {
                SObject[] records = qr.getRecords();
                for (int i = 0; i < records.length; i++) {
                    List<SObject> qrList = Arrays.asList(records);
                    oApexClassesList.addAll(qrList);
                }
                if (qr.isDone()) {
                    done = true;
                } else {
                    qr = toolingConnection.queryMore(qr.getQueryLocator());
                }
            }
        } else {
            System.out.println("No records found.");
        }
        SObject[] oApexClasses = new SObject[oApexClassesList.size()];
        for (int i = 0; i<oApexClassesList.size();i++){
            oApexClasses[i] = (SObject) oApexClassesList.get(i);
        }

        //SObject [] oApexClasses = toolingConnection.query("select Id, Name, Body from ApexClass where NamespacePrefix = null and name = 'Batch_MassUpdateAccountLinkJP'").getRecords();
        System.out.println("got first query output" + oApexClasses.length);
        // Delete existing MetadataContainer?
        SObject [] oMetacontainer1 = toolingConnection.query("select Id, Name from MetadataContainer where Name = 'UnusedApexMethods'").getRecords();
        if(oMetacontainer1 != null && oMetacontainer1.length > 0)
            toolingConnection.delete(new String[] {oMetacontainer1[0].getId()});

        // Create new MetadataContainer
        MetadataContainer container = new MetadataContainer();
        container.setName("UnusedApexMethods");
        MetadataContainer[] metadataContainers = new MetadataContainer[]{container};
        SaveResult[] saveResults = toolingConnection.create(metadataContainers);
        String containerId = saveResults[0].getId();

        // Create ApexClassMember's and associate them with the MetadataContainer
        List<ApexClassMember> apexClassMembers = new ArrayList<ApexClassMember>();
        for(SObject oApexClass : oApexClasses)
        {
            ApexClass apexClass = (ApexClass) oApexClass;
            ApexClassMember apexClassMember = new ApexClassMember();
            apexClassMember.setBody(apexClass.getBody());
            apexClassMember.setContentEntityId(apexClass.getId());
            apexClassMember.setMetadataContainerId(containerId);
            apexClassMembers.add(apexClassMember);
        }
        SObject[] oApexClassMembers = new SObject[apexClassMembers.size()];
        for (int i = 0; i<apexClassMembers.size();i++){
            oApexClassMembers[i] = (SObject) apexClassMembers.get(i);
        }
        saveResults = toolingConnection.create(oApexClassMembers);
        List<String> apexClassMemberIds = new ArrayList<String>();
        for(SaveResult saveResult : saveResults)
            apexClassMemberIds.add(saveResult.getId());
        System.out.println("size of apexClassMemberIds is " + apexClassMemberIds.size());
        String[] apxClassMemberIdStrings = new String[apexClassMemberIds.size()];
        for (int i = 0; i<apexClassMemberIds.size();i++){
            apxClassMemberIdStrings[i] = apexClassMemberIds.get(i);
        }

        // Create ContainerAysncRequest to deploy the (check only) the Apex Classes and thus obtain the SymbolTable's
        ContainerAsyncRequest ayncRequest = new ContainerAsyncRequest();
        ayncRequest.setMetadataContainerId(containerId);
        ayncRequest.setIsCheckOnly(true);
        ContainerAsyncRequest[] containerAsyncRequests = new ContainerAsyncRequest[]{ayncRequest};
        saveResults = toolingConnection.create(containerAsyncRequests);
        String containerAsyncRequestId = saveResults[0].getId();
        String[] contAsyncReqs = new String[] {containerAsyncRequestId};
        ayncRequest = (ContainerAsyncRequest) toolingConnection.retrieve("State", "ContainerAsyncRequest", contAsyncReqs)[0];
        while(ayncRequest.getState().equals("Queued"))
        {
            try {
                Thread.sleep(1 * 1000); // Wait for a second
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            ayncRequest = (ContainerAsyncRequest) toolingConnection.retrieve("State", "ContainerAsyncRequest", contAsyncReqs)[0];
        }
        // Query again the ApexClassMember's to retrieve the SymbolTable's
        SObject[] oApexClassMembersWithSymbols = (SObject[])toolingConnection.retrieve("ContentEntityId, SymbolTable", "ApexClassMember", apxClassMemberIdStrings);

        // Map declared methods and external method references from SymbolTable's
        Set<String> declaredMethods = new HashSet<String>();
        Set<String> methodReferences = new HashSet<String>();
        for(SObject oApexClassMember : oApexClassMembersWithSymbols)
        {
            ApexClassMember apexClassMember = (ApexClassMember) oApexClassMember;
            // List class methods defined and referenced
            SymbolTable symbolTable = apexClassMember.getSymbolTable();
            if(symbolTable==null) // No symbol table, then class likely is invalid
            {
                System.out.println("no symbol table");
                continue;
            }
            for(Method method : symbolTable.getMethods())
            {
                // Annotations are not exposed currently, following attempts to detect test methods to avoid giving false positives
                if(Arrays.asList(method.getAnnotations()).contains("isTest")){
                    System.out.println("this is a test");
                    continue;
                }
                // Skip Global methods as implicitly these are referenced
                if( Arrays.asList(method.getModifiers()).contains("global")){
                    System.out.println("this is global");
                    continue;
                }
                // Add the qualified method name to the list
                declaredMethods.add(symbolTable.getName() + "." + method.getName());
                // Any local references to this method?
                if(method.getReferences()!=null && method.getReferences().length > 0)
                    methodReferences.add(symbolTable.getName() + "." + method.getName());
            }
            // Add any method references this class makes to other class methods
            for(ExternalReference externalRef : symbolTable.getExternalReferences())
                for(ExternalMethod externalMethodRef : externalRef.getMethods())
                    methodReferences.add(externalRef.getName() + "." + externalMethodRef.getName());
        }

        // List declaredMethods with no external references
        TreeSet<String> unusedMethods = new TreeSet<String>();
        for(String delcaredMethodName : declaredMethods){
            if(!methodReferences.contains(delcaredMethodName)){
                unusedMethods.add(delcaredMethodName);
                System.out.println("Unused method name is " + delcaredMethodName);
            }
        }
    }

    public static void getUnusedApexMethods(){
            ConnectorConfig partnerConfig = new ConnectorConfig();
            partnerConfig.setUsername("myusername@salesforce.com");
            partnerConfig.setPassword("myPassword");
            partnerConfig.setAuthEndpoint("https://test.salesforce.com/services/Soap/u/43.0");
            // Creating the connection automatically handles login and stores
            // the session in partnerConfig
        try {
            new PartnerConnection(partnerConfig);
            ConnectorConfig toolingConfig = new ConnectorConfig();
            toolingConfig.setSessionId(partnerConfig.getSessionId());
            toolingConfig.setServiceEndpoint(partnerConfig.getServiceEndpoint().replace("/u/","/T/"));
            System.out.println(partnerConfig.getServiceEndpoint().replace("/u/","/T/"));
            System.out.println("session id is" + toolingConfig.getSessionId());
            toolingConnection = com.sforce.soap.tooling.Connector.newConnection(toolingConfig);

//            DescribeGlobalResult res = toolingConnection.describeGlobal();
//            System.out.println(res.toString());
        }
        catch (ConnectionException e){
            System.out.println("Exception " + e.getMessage());
        }
    }
}
