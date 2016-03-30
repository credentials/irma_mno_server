package org.irmacard.mno.web;

import org.irmacard.credentials.info.CredentialIdentifier;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.mno.common.EnrollmentStartMessage;
import org.irmacard.mno.common.PassportDataMessage;
import org.irmacard.mno.common.PassportVerificationResult;
import org.irmacard.mno.common.PassportVerificationResultMessage;
import org.jmrtd.lds.MRZInfo;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;

@Path("v2")
public class PassportEnrollmentResource extends GenericEnrollmentResource<PassportDataMessage> {
	@GET
	@Path("/start")
	@Produces(MediaType.APPLICATION_JSON)
	@Override
	public EnrollmentStartMessage start() {
		return super.start();
	}

	@POST
	@Path("/verify-passport")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Override
	public PassportVerificationResultMessage verifyDocument(PassportDataMessage documentData)
			throws InfoException {
		// Let super verify the passport message, and compute the resulting credentials
		PassportVerificationResultMessage msg = super.verifyDocument(documentData);

		// Check if super succeeded in verifying the passport
		if (msg.getResult() != PassportVerificationResult.SUCCESS) {
			return msg;
		}

		// Passport was succesfull; create issuing session with the API server
		HashMap<CredentialIdentifier, HashMap<String, String>> creds = getSession(documentData).getCredentialList();
		msg.setIssueQr(ApiClient.createIssuingSession(creds));

		return msg;
	}

	@Override
	protected HashMap<CredentialIdentifier, HashMap<String, String>> getCredentialList(EnrollmentSession session)
	throws InfoException {
		HashMap<CredentialIdentifier, HashMap<String, String>> credentials = new HashMap<>();
		MRZInfo mrz;
		try {
			mrz = session.getPassportDataMessage().getDg1File().getMRZInfo();
		} catch (NullPointerException e) {
			throw new InfoException("Cannot retrieve MRZ info");
		}

		SimpleDateFormat bacDateFormat = new SimpleDateFormat("yyMMdd");
		SimpleDateFormat hrDateFormat = new SimpleDateFormat("MMM d, y"); // Matches Android's default date format
		Date dob;
		Date expiry;

		try {
			dob = bacDateFormat.parse(mrz.getDateOfBirth());
			expiry = bacDateFormat.parse(mrz.getDateOfExpiry());
		}  catch (ParseException e) {
			e.printStackTrace();
			throw new InfoException("Failed to parse MRZ", e);
		}

		int[] lowAges = {12,16,18,21};
		credentials.put(new CredentialIdentifier(SCHEME_MANAGER, ISSUER, "ageLower"), ageAttributes(lowAges, dob));

		int[] highAges = {50, 60, 65, 75};
		credentials.put(new CredentialIdentifier(SCHEME_MANAGER, ISSUER, "ageHigher"), ageAttributes(highAges, dob));

		HashMap<String,String> nameAttributes = new HashMap<>();
		String[] nameParts = splitFamilyName(mrz.getPrimaryIdentifier());
		String firstnames = toTitleCase(joinStrings(mrz.getSecondaryIdentifierComponents()));
		// The first of the first names is not always the person's usual name ("roepnaam"). In fact, the person's
		// usual name need not even be in his list of first names at all. But given only the MRZ, there is no way of
		// knowing what is his/her usual name... So we can only guess.
		String firstname = toTitleCase(mrz.getSecondaryIdentifierComponents()[0]);

		nameAttributes.put("familyname", toTitleCase(nameParts[1]));
		nameAttributes.put("prefix", nameParts[0]);
		nameAttributes.put("firstnames", firstnames);
		nameAttributes.put("firstname", firstname);

		credentials.put(new CredentialIdentifier(SCHEME_MANAGER, ISSUER, "fullName"), nameAttributes);

		HashMap<String, String> idDocumentAttributes = new HashMap<String, String>();
		idDocumentAttributes.put("number", mrz.getDocumentNumber());
		idDocumentAttributes.put("expires", hrDateFormat.format(expiry));
		idDocumentAttributes.put("nationality", mrz.getNationality());
		if (mrz.getDocumentType() == MRZInfo.DOC_TYPE_ID1)
			idDocumentAttributes.put("type", "ID card");
		else if (mrz.getDocumentType() == MRZInfo.DOC_TYPE_ID3)
			idDocumentAttributes.put("type", "Passport");
		else
			idDocumentAttributes.put("type", "unknown");
		credentials.put(new CredentialIdentifier(SCHEME_MANAGER, ISSUER, "idDocument"), idDocumentAttributes);

		return credentials;
	}
}
