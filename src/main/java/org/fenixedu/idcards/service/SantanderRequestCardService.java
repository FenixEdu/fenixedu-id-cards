package org.fenixedu.idcards.service;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import javax.xml.bind.JAXBElement;
import javax.xml.namespace.QName;

import org.apache.cxf.endpoint.Client;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.interceptor.LoggingInInterceptor;
import org.apache.cxf.interceptor.LoggingOutInterceptor;
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.ws.addressing.WSAddressingFeature;
import org.fenixedu.academic.domain.Person;
import org.fenixedu.idcards.IdCardsConfiguration;
import org.fenixedu.idcards.domain.SantanderEntryNew;
import org.fenixedu.idcards.domain.SantanderPhotoEntry;
import org.fenixedu.idcards.utils.Action;
import org.fenixedu.idcards.utils.SantanderCardState;
import org.joda.time.DateTime;
import org.joda.time.Days;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;

import pt.ist.fenixframework.Atomic;
import pt.sibscartoes.portal.wcf.register.info.IRegisterInfoService;
import pt.sibscartoes.portal.wcf.register.info.dto.RegisterData;
import pt.sibscartoes.portal.wcf.tui.ITUIDetailService;
import pt.sibscartoes.portal.wcf.tui.dto.TUIResponseData;
import pt.sibscartoes.portal.wcf.tui.dto.TuiPhotoRegisterData;
import pt.sibscartoes.portal.wcf.tui.dto.TuiSignatureRegisterData;

public class SantanderRequestCardService {

    private static final Action ACTION_NEW = new Action("NOVO", "Novo");
    private static final Action ACTION_REMI = new Action("REMI", "Reemissão");
    private static final Action ACTION_RENU = new Action("RENU", "Renuvação");
    private static final Action ACTION_ATUA = new Action("ATUA", "Atualização de dados");
    private static final Action ACTION_CANC = new Action("CANC", "Cancelar Pedido");

    private static final String CANCELED = "Anulado";
    private static final String CANCELED_REMI = "Anulado (Reemissão)";
    private static final String CANCELED_RENU = "Anulado (Renovação)";
    private static final String MISSING_DATA = "Falta Dados Adicionais";
    private static final String READY_FOR_PRODUCTION = "Preparado para Produção";
    private static final String REMI_REQUEST = "Pedido de Reemissão";
    private static final String RENU_REQUEST = "Pedido de Renovação";
    private static final String REJECTED_REQUEST = "Emissão Rejeitada";
    private static final String ISSUED = "Expedido";
    private static final String PRODUCTION = "Em Produção";

    private static Logger logger = LoggerFactory.getLogger(SantanderRequestCardService.class);

    public static List<Action> getPersonAvailableActions(Person person) {
        //TODO Use SantanderCardState instead
        List<Action> actions = new LinkedList<>();
        String status = getRegister(person).get(1);

        /* If card is in canceled state
         * Or the person has no successful request
         * => Can only use ACTION_NEW
         */
        if (!hasCardRequest(person, status)) {
            actions.add(ACTION_NEW);
            return actions;
        }
        
        /* If card is in production state
         * => Can only use ACTION_CANC
         */
        if (status.equals(PRODUCTION)) {
            actions.add(ACTION_CANC);
            return actions;
        }

        /* If the card is in any of this states
         * => Can only use ACTION_ATUA and ACTION_CANC
         */
        if (specialCases(status)) {
            actions.add(ACTION_ATUA);
            actions.add(ACTION_CANC);
            return actions;
        }

        /* If the card is issued
         * => Can use ACTION_ATUA and ACTION_CANC
         * => ACTION_RENU can be used if there are 60 days or less until the expiration date
         * => ACTION_REMI can be used otherwise
         */
        if (status.equals(ISSUED)) {
            
            DateTime expiryDate = SantanderEntryNew.getLastSuccessfulEntry(person).getExpiryDate();

            if (Days.daysBetween(DateTime.now().withTimeAtStartOfDay(), expiryDate.withTimeAtStartOfDay()).getDays() < 60) {
                actions.add(ACTION_RENU);
            } else if (status.equals("Expedido")) {
                actions.add(ACTION_REMI);
            }

            actions.add(ACTION_ATUA);
            actions.add(ACTION_CANC);

            return actions;

        }

        /* Something went wrong!
         * Allow all actions
         */
        actions.add(ACTION_NEW);
        actions.add(ACTION_REMI);
        actions.add(ACTION_RENU);
        actions.add(ACTION_ATUA);
        actions.add(ACTION_CANC);

        return actions;
    }

    private static boolean hasCardRequest(Person person, String status) {

        if (SantanderEntryNew.getLastSuccessfulEntry(person) == null) {
            return false;
        }

        if (status.equals(CANCELED) || status.equals(CANCELED_REMI) || status.equals(CANCELED_RENU)) {
            return false;
        }

        return true;
    }

    private static boolean specialCases(String status) {

        if (status.equals(MISSING_DATA) || status.equals(READY_FOR_PRODUCTION) || status.equals(REMI_REQUEST)
                || status.equals(RENU_REQUEST) || status.equals(REJECTED_REQUEST)) {
            return true;
        }

        return false;
    }

    public static List<String> getRegister(Person person) {
        List<String> result = new ArrayList<>();

        JaxWsProxyFactoryBean factory = new JaxWsProxyFactoryBean();

        factory.setServiceClass(IRegisterInfoService.class);
        factory.setAddress("https://portal.sibscartoes.pt/tstwcfv2/services/RegisterInfoService.svc");
        factory.setBindingId("http://schemas.xmlsoap.org/wsdl/soap12/");
        factory.getFeatures().add(new WSAddressingFeature());

        //Add loggers to request
        factory.getInInterceptors().add(new LoggingInInterceptor());
        factory.getOutInterceptors().add(new LoggingOutInterceptor());

        IRegisterInfoService port = (IRegisterInfoService) factory.create();

        /*define WSDL policy*/
        Client client = ClientProxy.getClient(port);
        HTTPConduit http = (HTTPConduit) client.getConduit();

        //Add username and password properties
        http.getAuthorization().setUserName(IdCardsConfiguration.getConfiguration().sibsWebServiceUsername());
        http.getAuthorization().setPassword(IdCardsConfiguration.getConfiguration().sibsWebServicePassword());

        final String userName = Strings.padEnd(person.getUsername(), 10, 'x');

        RegisterData statusInformation = port.getRegister(userName);

        result.add(statusInformation.getStatus().getValue());
        result.add(statusInformation.getStatusDate().getValue().replaceAll("-", "/"));
        result.add(statusInformation.getStatusDescription().getValue());

        logger.debug("Result: " + result.get(1) + " : " + result.get(0) + " - " + result.get(2));

        return result;
    }

    @Atomic(mode = Atomic.TxMode.WRITE)
    public static void createRegister(String tuiEntry, Person person) throws SantanderCardMissingDataException {

        if (tuiEntry == null) {
            logger.debug("Null tuiEntry for user " + person.getUsername());
            return;
        }

        logger.debug("Entry: " + tuiEntry);
        logger.debug("Entry size: " + tuiEntry.length());
        
        TuiPhotoRegisterData photo = getOrCreateSantanderPhoto(person);
        TuiSignatureRegisterData signature = new TuiSignatureRegisterData();

        SantanderEntryNew entry = person.getCurrentSantanderEntry();
        SantanderCardState cardState = entry.getState();

        /*
         * If there was an error on the previous entry update it
         * Else create a new entry
         */
        if (cardState == SantanderCardState.FENIX_ERROR || cardState == SantanderCardState.RESPONSE_ERROR
                || cardState == SantanderCardState.REJECTED) {
            entry.reset(person);
        } else {
            entry = new SantanderEntryNew(person);
        }

        JaxWsProxyFactoryBean factory = new JaxWsProxyFactoryBean();

        factory.setServiceClass(ITUIDetailService.class);
        factory.setAddress("https://portal.sibscartoes.pt/tstwcfv2/services/TUIDetailService.svc");
        factory.setBindingId("http://schemas.xmlsoap.org/wsdl/soap12/");
        factory.getFeatures().add(new WSAddressingFeature());

        //Add loggers to request
        factory.getInInterceptors().add(new LoggingInInterceptor());
        factory.getOutInterceptors().add(new LoggingOutInterceptor());

        ITUIDetailService port = (ITUIDetailService) factory.create();

        /*define WSDL policy*/
        Client client = ClientProxy.getClient(port);
        HTTPConduit http = (HTTPConduit) client.getConduit();
        //Add username and password properties
        http.getAuthorization().setUserName(IdCardsConfiguration.getConfiguration().sibsWebServiceUsername());
        http.getAuthorization().setPassword(IdCardsConfiguration.getConfiguration().sibsWebServicePassword());

        TUIResponseData tuiResponse;

        try {
            tuiResponse = port.saveRegister(tuiEntry, photo, signature);
        } catch (Throwable t) {
            entry.updateEntry(person, SantanderCardState.RESPONSE_ERROR, DateTime.now(), "", "", false,
                    "Erro a comunicar com o santander");
            return;
        }

        String tuiStatus = tuiResponse.getStatus() == null || tuiResponse.getStatus().getValue() == null ? "" : tuiResponse
                .getStatus().getValue().trim();
        String tuiErrorDescription = tuiResponse.getStatusDescription() == null
                || tuiResponse.getStatusDescription().getValue() == null ? "" : tuiResponse.getStatusDescription().getValue()
                        .trim();
        String tuiResponseLine = tuiResponse.getTuiResponseLine() == null
                || tuiResponse.getTuiResponseLine().getValue() == null ? "" : tuiResponse.getTuiResponseLine().getValue().trim();

        logger.debug("Status: " + tuiStatus);
        logger.debug("Description: " + tuiErrorDescription);
        logger.debug("Line: " + tuiResponseLine);

        boolean registerSuccessful = !tuiStatus.isEmpty() || !tuiStatus.toLowerCase().equals("error");

        entry.updateEntry(person, SantanderCardState.NEW, DateTime.now(), tuiEntry, tuiResponseLine, registerSuccessful,
                tuiErrorDescription);

    }

    private static TuiPhotoRegisterData getOrCreateSantanderPhoto(Person person) throws SantanderCardMissingDataException {
        final QName FILE_NAME =
                new QName("http://schemas.datacontract.org/2004/07/SibsCards.Wcf.Services.DataContracts", "FileName");
        final QName FILE_EXTENSION =
                new QName("http://schemas.datacontract.org/2004/07/SibsCards.Wcf.Services.DataContracts", "Extension");
        final QName FILE_CONTENTS =
                new QName("http://schemas.datacontract.org/2004/07/SibsCards.Wcf.Services.DataContracts", "FileContents");
        final QName FILE_SIZE = new QName("http://schemas.datacontract.org/2004/07/SibsCards.Wcf.Services.DataContracts", "Size");

        final String EXTENSION = ".jpeg";

        TuiPhotoRegisterData photo = new TuiPhotoRegisterData();

        SantanderPhotoEntry photoEntry;

        try {
            photoEntry = SantanderPhotoEntry.getOrCreatePhotoEntryForPerson(person);
        } catch (Throwable t) {
            throw new SantanderCardMissingDataException("Missing photo");
        }

        byte[] photo_contents = photoEntry.getPhotoAsByteArray();

        photo.setFileContents(new JAXBElement<>(FILE_CONTENTS, byte[].class, photo_contents));
        photo.setSize(new JAXBElement<>(FILE_SIZE, String.class, Integer.toString(photo_contents.length)));
        photo.setExtension(new JAXBElement<>(FILE_EXTENSION, String.class, EXTENSION));
        photo.setFileName(new JAXBElement<>(FILE_NAME, String.class, "foto")); //TODO

        return photo;
    }
}