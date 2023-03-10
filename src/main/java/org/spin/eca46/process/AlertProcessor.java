/******************************************************************************
 * Product: ADempiere ERP & CRM Smart Business Solution                       *
 * Copyright (C) 2006-2017 ADempiere Foundation, All Rights Reserved.         *
 * This program is free software, you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * or (at your option) any later version.										*
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY, without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program, if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 * For the text or an alternative of this public license, you may reach us    *
 * or via info@adempiere.net or http://www.adempiere.net/license.html         *
 *****************************************************************************/

package org.spin.eca46.process;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.impexp.ArrayExcelExporter;
import org.compiere.model.MAlert;
import org.compiere.model.MAlertProcessor;
import org.compiere.model.MAlertProcessorLog;
import org.compiere.model.MAlertRule;
import org.compiere.model.MSysConfig;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.DisplayType;
import org.compiere.util.Env;
import org.compiere.util.Msg;
import org.compiere.util.TimeUtil;
import org.compiere.util.Trx;
import org.compiere.util.ValueNamePair;
import org.spin.queue.notification.DefaultNotifier;
import org.spin.queue.util.QueueLoader;

/** 
 * 	Generated Process for (Product Internal Use)
 *  @author Jorg Janke
 *  @version $Id: RequestProcessor.java,v 1.3 2006/07/30 00:53:33 jjanke Exp $
 *  @version Release 3.9.3
 */
public class AlertProcessor extends AlertProcessorAbstract {
	
	/**	Initial work time			*/
	private long 				startWork;
	/**	The Concrete Model			*/
	private MAlertProcessor		alertProcessor = null;
	/**	Last Summary				*/
	private StringBuffer 		m_summary = new StringBuffer();
	/**	Last Error Msg				*/
	private StringBuffer 		m_errors = new StringBuffer();
	
	@Override
	protected void prepare() {
		super.prepare();
		if(getAlertProcessorId() <= 0) {
			throw new AdempiereException("@AD_AlertProcessor_ID@ @NotFound@");
		}
		alertProcessor = new MAlertProcessor(getCtx(), getAlertProcessorId(), get_TrxName());
	}

	@Override
	protected String doIt() throws Exception {
		startWork = System.currentTimeMillis();
		m_summary = new StringBuffer();
		m_errors = new StringBuffer();
		//
		int count = 0;
		int countError = 0;
		MAlert[] alerts = alertProcessor.getAlerts(false);
		for (int i = 0; i < alerts.length; i++)
		{
			if (!processAlert(alerts[i]))
				countError++;
			count++;
		}
		//
		String summary = "Total=" + count;
		if (countError > 0)
			summary += ", Not processed=" + countError;
		summary += " - ";
		m_summary.insert(0, summary);
		//
		int no = alertProcessor.deleteLog();
		m_summary.append("Logs deleted=").append(no);
		if (alertProcessor.get_TrxName() == null) {
			Trx.run(this::addAlertProcessorLog);
		} else {
			addAlertProcessorLog(alertProcessor.get_TrxName());
		}
		return TimeUtil.formatElapsed(new Timestamp(startWork));
	}
	
	/**
	 * Add Alert Processor Log
	 * @param trxName
	 */
	private void addAlertProcessorLog(String trxName) {
		MAlertProcessorLog alertProcessorLog = new MAlertProcessorLog(alertProcessor, m_summary.toString(), trxName);
		alertProcessorLog.setReference(TimeUtil.formatElapsed(new Timestamp(startWork)));
		alertProcessorLog.setTextMsg(m_errors.toString());
		alertProcessorLog.saveEx();
	}

	/**
	 * 	Process Alert
	 *	@param alert alert
	 *	@return true if processed
	 */
	private boolean processAlert (MAlert alert)
	{
		if (!alert.isValid())
			return false;
		log.info("" + alert);

		StringBuffer message = new StringBuffer(alert.getAlertMessage())
			.append(Env.NL);
		//
		boolean valid = true;
		boolean processed = false;
		ArrayList<File> attachments = new ArrayList<File>();
		MAlertRule[] rules = alert.getRules(false);
		for (int i = 0; i < rules.length; i++)
		{
			if (i > 0)
				message.append(Env.NL);
			String trxName = null;		//	assume r/o
			
			MAlertRule rule = rules[i];
			if (!rule.isValid())
				continue;
			log.fine("" + rule);
			
			//	Pre
			String sql = rule.getPreProcessing();
			if (sql != null && sql.length() > 0)
			{
				int no = DB.executeUpdate(sql, false, trxName);
				if (no == -1)
				{
					ValueNamePair error = CLogger.retrieveError();
					rule.setErrorMsg("Pre=" + error.getName());
					m_errors.append("Pre=" + error.getName());
					rule.setIsValid(false);
					rule.saveEx();
					valid = false;
					break;
				}
			}	//	Pre
			
			//	The processing
			sql = rule.getSql(true);
			try
			{
				String text = null;
				if (MSysConfig.getBooleanValue("ALERT_SEND_ATTACHMENT_AS_XLS", true, Env.getAD_Client_ID(getCtx())))
					text = getExcelReport(rule, sql, trxName, attachments);
				else
					text = getPlainTextReport(rule, sql, trxName, attachments);
				if (text != null && text.length() > 0)
				{
					message.append(text);
					processed = true;
				}
			}
			catch (Exception e)
			{
				rule.setErrorMsg("Select=" + e.getLocalizedMessage());
				m_errors.append("Select=" + e.getLocalizedMessage());
				rule.setIsValid(false);
				rule.saveEx();
				valid = false;
				break;
			}

			//	Post
			sql = rule.getPostProcessing();
			if (sql != null && sql.length() > 0)
			{
				int no = DB.executeUpdate(sql, false, trxName);
				if (no == -1)
				{
					ValueNamePair error = CLogger.retrieveError();
					rule.setErrorMsg("Post=" + error.getName());
					m_errors.append("Post=" + error.getName());
					rule.setIsValid(false);
					rule.saveEx();
					valid = false;
					break;
				}
			}	//	Post
		}	//	 for all rules
		
		//	Update header if error
		if (!valid)
		{
			alert.setIsValid(false);
			alert.saveEx();
			return false;
		}
		
		//	Nothing to report
		if (!processed)
		{
			m_summary.append(alert.getName()).append("=No Result - ");
			return true;
		}
		
		//
		// Report footer - Date Generated
		DateFormat df = DisplayType.getDateFormat(DisplayType.DateTime);
		message.append("\n\n");
		message.append(Msg.translate(getCtx(), "Date")).append(" : ")
				.append(df.format(new Timestamp(System.currentTimeMillis())));
		
		Collection<Integer> users = alert.getRecipientUsers();
		int countMail = notifyUsers(users, alert.getAlertSubject(), message.toString(), attachments);
		
		m_summary.append(alert.getName()).append(" (EMails+Notes=").append(countMail).append(") - ");
		return valid;
	}	//	processAlert
	
	/**
	 * Notify users
	 * @param users AD_User_ID list
	 * @param subject email subject
	 * @param message email message
	 * @param attachments 
	 * @return how many email were sent
	 */
	private int notifyUsers(Collection<Integer> users, String subject, String message, Collection<File> attachments) {
		AtomicInteger countMail = new AtomicInteger(0);
		users.forEach(userId -> {
			Trx.run(transactionName -> {
				//	Get instance for notifier
				DefaultNotifier notifier = (DefaultNotifier) QueueLoader.getInstance().getQueueManager(DefaultNotifier.QUEUETYPE_DefaultNotifier)
						.withContext(getCtx())
						.withTransactionName(transactionName);
				//	Send notification to queue
				notifier
					.clearMessage()
					.withApplicationType(DefaultNotifier.DefaultNotificationType_UserDefined)
					.addRecipient(userId)
					.withText(message)
					.withDescription(subject);
				//	Add Attachments
				attachments.forEach(attachment -> notifier.addAttachment(attachment));
				//	Add to queue
				notifier.addToQueue();
				countMail.incrementAndGet();
			});
		});
		return countMail.get();
	}
	
	/**
	 * Get Alert Data
	 * @param sql
	 * @param trxName
	 * @return data
	 * @throws Exception
	 */
	private ArrayList<ArrayList<Object>> getData (String sql, String trxName) throws Exception
	{
		ArrayList<ArrayList<Object>> data = new ArrayList<ArrayList<Object>>();
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		Exception error = null;
		try
		{
			pstmt = DB.prepareStatement (sql, trxName);
			rs = pstmt.executeQuery ();
			ResultSetMetaData meta = rs.getMetaData();
			boolean isFirstRow = true;
			while (rs.next ())
			{
				ArrayList<Object> header = (isFirstRow ? new ArrayList<Object>() : null);
				ArrayList<Object> row = new ArrayList<Object>();
				for (int col = 1; col <= meta.getColumnCount(); col++)
				{
					if (isFirstRow) {
						String columnName = meta.getColumnLabel(col);
						header.add(columnName);
					}
					Object o = rs.getObject(col);
					row.add(o);
				}	//	for all columns
				if (isFirstRow)
					data.add(header);
				data.add(row);
				isFirstRow = false;
			}
		}
		catch (Throwable e)
		{
			log.log(Level.SEVERE, sql, e);
			if (e instanceof Exception)
				error = (Exception)e;
			else
				error = new Exception(e.getMessage(), e);
		}
		finally
		{
			DB.close(rs, pstmt);
			rs = null; pstmt = null;
		}
		
		//	Error occurred
		if (error != null)
			throw new Exception ("(" + sql + ") " + Env.NL 
				+ error.getLocalizedMessage());
		
		return data;
	}	//	getData
	
	/**
	 * Get Plain Text Report (old functionality) 
	 * @param rule (ignored)
	 * @param sql sql select
	 * @param trxName transaction
	 * @param attachments (ignored)
	 * @return list of rows & values
	 * @throws Exception
	 * @deprecated
	 */
	private String getPlainTextReport(MAlertRule rule, String sql, String trxName, Collection<File> attachments)
	throws Exception
	{
		StringBuffer result = new StringBuffer();
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		Exception error = null;
		try
		{
			pstmt = DB.prepareStatement (sql, trxName);
			rs = pstmt.executeQuery ();
			ResultSetMetaData meta = rs.getMetaData();
			while (rs.next ())
			{
				result.append("------------------").append(Env.NL);
				for (int col = 1; col <= meta.getColumnCount(); col++)
				{
					result.append(meta.getColumnLabel(col)).append(" = ");
					result.append(rs.getString(col));
					result.append(Env.NL);
				}	//	for all columns
			}
			if (result.length() == 0)
				log.fine("No rows selected");
		}
		catch (Throwable e)
		{
			log.log(Level.SEVERE, sql, e);
			if (e instanceof Exception)
				error = (Exception)e;
			else
				error = new Exception(e.getMessage(), e);
		}
		finally
		{
			DB.close(rs, pstmt);
			rs = null; pstmt = null;
		}
		
		//	Error occurred
		if (error != null)
			throw new Exception ("(" + sql + ") " + Env.NL 
				+ error.getLocalizedMessage());
		
		return result.toString();
	}

	/**
	 * Get Excel Report
	 * @param rule
	 * @param sql
	 * @param trxName
	 * @param attachments
	 * @return summary message to be added into mail content
	 * @throws Exception
	 */
	private String getExcelReport(MAlertRule rule, String sql, String trxName, Collection<File> attachments)
	throws Exception
	{
		ArrayList<ArrayList<Object>> data = getData(sql, trxName);
		if (data.size() <= 1)
			return null;
		// File
		File file = rule.createReportFile("xls");
		//
		ArrayExcelExporter exporter = new ArrayExcelExporter(getCtx(), data, false);
		exporter.export(file, null, false);
		attachments.add(file);
		String msg = rule.getName() + " (@SeeAttachment@ "+file.getName()+")"+Env.NL;
		return Msg.parseTranslation(Env.getCtx(), msg);
	}
	
	/**
	 * 	Get Server Info
	 *	@return info
	 */
	public String getServerInfo()
	{
		return "Last=" + m_summary.toString();
	}	//	getServerInfo
}