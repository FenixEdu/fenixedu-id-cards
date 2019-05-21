package org.fenixedu.idcards.dto;

import com.google.common.io.BaseEncoding;
import org.fenixedu.idcards.domain.SantanderCardInfo;
import org.fenixedu.idcards.domain.SantanderCardState;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import java.util.List;
import java.util.stream.Collectors;

public class SantanderCardDto {
    public String cardId;
    public String istId;
    public String role;
    public String expiryDate;
    public String cardName;
    public String identificationNumber;
    public String photo;
    public SantanderCardState currentState;
    public List<SantanderStateDto> history;

    public SantanderCardDto(SantanderCardInfo cardInfo) {
        DateTime expiryDate = cardInfo.getExpiryDate();
        if (expiryDate != null) {
            DateTimeFormatter dateFormatter = DateTimeFormat.forPattern("yyyy/MM");
            this.expiryDate = dateFormatter.print(expiryDate);
        }
        this.cardId = cardInfo.getExternalId();
        this.istId = cardInfo.getIdentificationNumber();
        this.cardName = cardInfo.getCardName();
        this.role = cardInfo.getRole();
        this.identificationNumber = cardInfo.getIdentificationNumber();
        this.photo = BaseEncoding.base64().encode(cardInfo.getPhoto());
        this.currentState = cardInfo.getCurrentState();
        this.history = cardInfo.getSantanderCardStateTransitionsSet()
                .stream()
                .map(SantanderStateDto::new)
                .collect(Collectors.toList());
    }
}