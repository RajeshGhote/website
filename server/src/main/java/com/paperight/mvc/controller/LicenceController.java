package com.paperight.mvc.controller;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import au.com.bytecode.opencsv.CSVWriter;

import com.paperight.licence.Licence;
import com.paperight.licence.LicenceService;
import com.paperight.licence.LicenceStatus;
import com.paperight.product.Product;
import com.paperight.user.Address;
import com.paperight.user.AddressContextType;
import com.paperight.user.Company;

@Controller
public class LicenceController {
	
	@Autowired
	private LicenceService licenceService;
	
	private Logger logger = LoggerFactory.getLogger(LicenceController.class);
	
	@RequestMapping(value = "/rest/licence/generate/{licenceId}", method = RequestMethod.GET, produces = "application/json")
	public @ResponseBody Object generate(@PathVariable Long licenceId) {
		Response response = new Response();
		try {
			Licence licence = Licence.find(licenceId);
			if (licence == null) {
				response.setResponseObject("Licence not found");
			} else if (licence.getStatus() == LicenceStatus.NEW) {
				licenceService.generateFile(licence);
				licence.setStatus(LicenceStatus.GENERATED);
				licence.merge();
				response.setStatus(com.paperight.mvc.controller.ResponseStatus.OK);
			} else if (licence.getStatus() == LicenceStatus.CANCELLED) {
				response.setResponseObject("Licence cancelled");
			} else {
				response.setResponseObject("Licence already downloaded");
			}
		} catch (Exception e) {
			logger.error("Unable to generate licenceid: " + licenceId, e);
			response.setResponseObject(e.getMessage());
		}
		return response;
	}
	
	@RequestMapping(value="/licence/search", method = {RequestMethod.GET, RequestMethod.POST} )
	public String search(@ModelAttribute LicenceSearch licenceSearch, Model model) {
		List<Licence> licences = searchLicences(licenceSearch);
		model.addAttribute("licenceSearch", licenceSearch);
		model.addAttribute("licences", licences);
		return "licence/search";
	}
	
	private List<Licence> searchLicences(LicenceSearch licenceSearch) {
		if (licenceSearch.getFromDate() == null && licenceSearch.getToDate() == null) {
			return new ArrayList<Licence>();
		} else {
			return Licence.findByDateRange(licenceSearch.getFromDate(), licenceSearch.getToDate());
		}
	}
	
	@RequestMapping(value="/licence/export", method = RequestMethod.GET )
	public void export(@ModelAttribute LicenceSearch licenceSearch, HttpServletResponse response) throws Exception {
		List<Licence> licences = searchLicences(licenceSearch);
		String csvContent = buildCsv(licences);			
		response.setContentType("text/csv");
		String filename = "licence_export_" + new DateTime().toString("yyyyMMdd-hhmmss");
		response.addHeader("content-disposition", "attachment; filename=" + filename + ".csv");
		InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes());
		try {
			IOUtils.copy(inputStream, response.getOutputStream());
			response.flushBuffer();
		} finally {
			inputStream.close();
		}
		
	}
	
	private String buildCsv(List<Licence> licences) throws IOException {
		List<String[]> csvLines = new ArrayList<String[]>();
		String[] csvHeader = { "RightsholderName", "Owner", "OwnerEmailAddress", "TransactionDate", "Title", "Subtitle", "Edition", "Author", "Identifier", "SubjectArea", "OutletName", "OutletId", "OutletCity", "OutletCountry", "OutletCurrency", "OutletServiceCharge", "NumberOfCopies", "Layout", "DoubleSidedSheets", "CreditsReserved", "DownloadedYN", "CreditsCharged", "DollarEquivalent", "PaperightFee", "AmountOwingToRightsholder" };
		csvLines.add(csvHeader);
		for (Licence licence : licences) {
			Product product = licence.getProduct();
			Address address = licence.getCompany().getAddressContextByType(AddressContextType.DEFAULT_PRIMARY).getAddress();
			Company owner = product.getOwnerCompany();
			String ownerEmail = owner.getEmail();
			String[] csvLine = new String[24];
			csvLine[0] = product.getPublisher();//RightsholderName
			csvLine[1] = owner.getName();
			if (ownerEmail != null ) {
			    csvLine[2] = ownerEmail;//Owner email address
			} else {
			    csvLine[2] = "";//Owner email address
			}
			csvLine[3] = licence.getCreatedDate().toString();//TransactionDate
			csvLine[4] = product.getTitle();//Title
			csvLine[5] = product.getSubTitle();//Subtitle
			csvLine[6] = product.getEdition();//Edition
			csvLine[7] = product.getPrimaryCreators();//Author
			csvLine[8] = product.getIdentifier();//Identifier
			csvLine[9] = product.getSubjectArea();//SubjectArea
			csvLine[10] = licence.getCompany().getName();//OutletName
			csvLine[11] = licence.getCompany().getId().toString();//OutletId
			csvLine[12] = address.getAddressLine4();//OutletCity
			csvLine[13] = address.getCountry().getName();//OutletCountry
			csvLine[14] = licence.getCurrencyCode();//OutletCurrency
			csvLine[15] = licence.getOutletCharge().toString();//OutletServiceCharge
			csvLine[16] = licence.getNumberOfCopies() + "";//NumberOfCopies
			csvLine[17] = licence.getPageLayout().toString();//Layout
			csvLine[18] = licence.getPageExtent() + "";//DoubleSidedSheets
			csvLine[19] = licence.getCostInCredits().toString();//CreditsReserved
			csvLine[20] = licence.isDownloaded() ? "Y" : "N" ;//DownloadedYN
			csvLine[21] = licence.isDownloaded() ? licence.getCostInCredits().toString() : "";//CreditsCharged
			BigDecimal dollarEquivalent = licence.getCostInCredits().multiply(licence.getPaperightCreditToBaseCurrencyRate()).setScale(2, RoundingMode.UP);
			BigDecimal paperightFree = dollarEquivalent.multiply(BigDecimal.valueOf(0.2)).setScale(2, RoundingMode.HALF_UP);
			csvLine[22] = dollarEquivalent.toString();//DollarEquivalent
			csvLine[23] = paperightFree.toString();//PaperightFee
			csvLine[24] = dollarEquivalent.subtract(paperightFree).toString();//AmountOwingToRightsholder
			csvLines.add(csvLine);
		}
		Writer writer = new StringWriter();
		CSVWriter csvWriter = new CSVWriter(writer);
		try {
			csvWriter.writeAll(csvLines);
			return writer.toString();
		} finally {
			csvWriter.close();
		} 
	}

}

class ResponseError {
	
	private Long licenceId;
	private String error;
	
	public ResponseError(Long id, String error) {
		this.licenceId = id;
		this.error = error;
	}
	
	public Long getLicenceId() {
		return licenceId;
	}
	
	public String getError() {
		return error;
	}
}

class LicenceSearch {
	
	@DateTimeFormat(iso = ISO.DATE)
	private DateTime fromDate;
	
	@DateTimeFormat(iso = ISO.DATE)
	private DateTime toDate;
	
	public DateTime getFromDate() {
		return fromDate;
	}
	
	public void setFromDate(DateTime fromDate) {
		this.fromDate = fromDate.withHourOfDay(0).withMinuteOfHour(0).withSecondOfMinute(0);
	}
	
	public DateTime getToDate() {
		return toDate;
	}
	
	public void setToDate(DateTime toDate) {
		this.toDate = toDate.withHourOfDay(23).withMinuteOfHour(59).withSecondOfMinute(59);
	}
	
}
