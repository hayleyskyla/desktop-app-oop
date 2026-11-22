package ph.agritourism.ui

import ph.agritourism.data.Database
import ph.agritourism.model.Booking
import ph.agritourism.model.FarmerOrder
import ph.agritourism.model.Receipt
import java.awt.Desktop
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window
import java.awt.image.BufferedImage
import java.awt.print.PageFormat
import java.awt.print.Printable
import java.awt.print.PrinterJob
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** Desktop replacements for the browser's window.print() / Blob downloads. */
object Printing {
    private fun esc(s: String?) = (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private val exportDir get() = File(Database.defaultFile().parentFile, "exports").apply { mkdirs() }

    private const val CSS = """
*{box-sizing:border-box;margin:0;padding:0}body{font-family:-apple-system,"Segoe UI",Roboto,Arial,sans-serif;background:#F4F7F4;color:#1E293B;padding:24px;display:flex;justify-content:center}
.c{max-width:680px;width:100%;background:#fff;border-radius:24px;box-shadow:0 20px 40px rgba(0,0,0,.08);overflow:hidden;border:1px solid #CBD5E1}
.h{background:#144824;color:#fff;padding:24px;display:flex;justify-content:space-between;align-items:center}.b{font-size:20px;font-weight:900}
.badge{font-size:11px;font-weight:800;background:#86EFAC;color:#064E3B;padding:4px 10px;border-radius:9999px;text-transform:uppercase}
.ct{padding:28px}.ref{display:inline-block;font-family:monospace;font-size:12px;font-weight:900;background:#1E562F;color:#fff;padding:4px 12px;border-radius:8px;margin-bottom:12px}
h1{font-size:24px;font-weight:900;color:#144824;margin-bottom:4px}.sub{font-size:14px;font-weight:600;color:#4B6F55;margin-bottom:24px}
.g{display:grid;grid-template-columns:repeat(2,1fr);gap:12px;margin-bottom:24px}.cd{background:#F8FAFC;border:1px solid #E2E8F0;padding:14px;border-radius:14px}
.l{font-size:10px;font-weight:800;color:#64748B;text-transform:uppercase;margin-bottom:4px}.v{font-size:13px;font-weight:800;color:#0F172A}
.i{background:#F0FDF4;border:1px solid #BBF7D0;border-radius:14px;padding:16px;margin-bottom:24px;font-size:12px;color:#166534;line-height:1.5}
table{width:100%;border-collapse:collapse;margin:16px 0}th,td{text-align:left;padding:8px;border-bottom:1px solid #E2E8F0;font-size:12px}th{background:#F1F5F9}
.t{text-align:right;font-size:15px;font-weight:900;color:#1E562F;margin-top:8px}.bar{background:#F1F5F9;padding:16px 28px;display:flex;justify-content:flex-end;border-top:1px solid #E2E8F0}
button{background:#1E562F;color:#fff;border:none;padding:10px 20px;border-radius:10px;font-weight:800;cursor:pointer}
@media print{body{background:#fff;padding:0}.c{box-shadow:none;border:1px solid #000}.bar{display:none!important}}"""

    private fun page(title: String, body: String, autoPrint: Boolean) = """<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8"><title>${esc(title)}</title><style>$CSS</style></head>
<body${if (autoPrint) " onload=\"setTimeout(()=>window.print(),400)\"" else ""}><div class="c">$body<div class="bar"><button onclick="window.print()">Print</button></div></div></body></html>"""

    private fun card(l: String, v: String) = """<div class="cd"><div class="l">${esc(l)}</div><div class="v">${esc(v)}</div></div>"""

    fun ticketHtml(b: Booking, autoPrint: Boolean = false) = page("AgriTourism Pass - ${b.bookingCode}", """
<div class="h"><div><div class="b">AgriTourism Philippines</div><p style="font-size:11px;opacity:.85;margin-top:2px">Official Verified Farm E-Ticket Pass</p></div><span class="badge">Confirmed Entry</span></div>
<div class="ct"><span class="ref">Pass Reference: ${esc(b.bookingCode)}</span><h1>${esc(b.farmName)}</h1><p class="sub">${esc(b.activity)}</p>
<div class="g">${card("Tour Date", b.tourDate)}${card("Scheduled Slot", b.tourTime)}${card("Lead Visitor", b.customerName)}${card("Party Size", "${b.participants} Guests")}
${card("Assigned Guide", b.assignedGuide ?: "To be assigned")}${card("Total Admission", "₱${money(b.totalAmount)} (${b.paymentMethod ?: "Pay at the Farm"})")}</div>
<div class="i"><strong>Gate Instructions:</strong><br>Please arrive 15 minutes before your time slot and present booking reference ${esc(b.bookingCode)} at the farm entrance. Includes complimentary native welcome drink and farm basket.</div></div>""", autoPrint)

    fun receiptHtml(r: Receipt) = page(r.receiptNumber, """
<div class="h"><div><div class="b">AgriTourism Philippines</div><p style="font-size:11px;opacity:.85">Official Electronic Sales Invoice &amp; Receipt</p></div><span class="badge">${esc(r.paymentStatus)}</span></div>
<div class="ct"><span class="ref">${esc(r.receiptNumber)}</span><p class="sub">${esc(r.issueDate)} · Auth: ${esc(r.authReference)}</p>
<div class="g">${card("Customer", "${r.customerName} · ${r.customerPhone}")}${card("Address / Email", r.customerAddress)}${card("Accredited Farm Host", r.farmName)}${card("Host", r.hostFarmerName)}</div>
<table><tr><th>Item Description</th><th>Qty</th><th>Price</th><th>Amount</th></tr>${r.items.joinToString("") { "<tr><td>${esc(it.name)}</td><td>${it.quantity}</td><td>₱${money(it.unitPrice)}</td><td>₱${money(it.total)}</td></tr>" }}</table>
<p style="text-align:right;font-size:12px">Subtotal: ₱${money(r.subtotal)}</p>${if (r.deliveryFee > 0) "<p style=\"text-align:right;font-size:12px\">Logistics &amp; Direct Delivery: ₱${money(r.deliveryFee)}</p>" else ""}
<p class="t">Total Amount Paid: ₱${money(r.totalAmount)}</p><p style="margin-top:16px;font-size:11px;color:#166534;font-weight:800">PAID &amp; VERIFIED · AGRITOURISM PH · ${esc(r.paymentMethod)}</p></div>""", true)

    fun bookingDossierHtml(b: Booking) = page("Booking ${b.bookingCode}", """
<div class="h"><div class="b">Official AgriTourism Tour Voucher</div><span class="badge">${esc(b.status)}</span></div>
<div class="ct"><span class="ref">${esc(b.bookingCode)}</span><h1>${esc(b.farmName)}</h1><p class="sub">${esc(b.activity)}</p>
<div class="g">${card("Tour Date", b.tourDate)}${card("Scheduled Time", b.tourTime)}${card("Tourist / Guest", b.customerName)}${card("Group Size", "${b.participants} Persons")}
${card("Contact", "${b.customerPhone ?: "—"} · ${b.customerEmail ?: "walk-in guest"}")}${card("Attendance", b.attendanceStatus ?: "Pending Confirmation")}
${card("Assigned Guide", b.assignedGuide ?: "Not yet assigned")}${card("Total", "₱${money(b.totalAmount)}")}</div></div>""", true)

    fun manifestHtml(bookings: List<Booking>, farm: String) = page("Guest Manifest", """
<div class="h"><div class="b">${esc(farm)} · Visitor Manifest</div></div><div class="ct">
<table><tr><th>Code</th><th>Date</th><th>Time</th><th>Guest</th><th>Activity</th><th>Pax</th><th>Status</th></tr>
${bookings.joinToString("") { "<tr><td>${esc(it.bookingCode)}</td><td>${esc(it.tourDate)}</td><td>${esc(it.tourTime)}</td><td>${esc(it.customerName)}</td><td>${esc(it.activity)}</td><td>${it.participants}</td><td>${esc(it.status)}</td></tr>" }}</table></div>""", true)

    fun orderHtml(orders: List<FarmerOrder>, title: String) = page(title, """
<div class="h"><div class="b">${esc(title)}</div></div><div class="ct">${orders.joinToString("") { o -> """
<span class="ref">#${esc(o.orderNumber)} · ${esc(o.fulfillmentStatus)}</span><p class="sub">${esc(o.customerName)} · ${esc(o.customerPhone)}<br>${esc(o.customerAddress)}</p>
<table><tr><th>Item</th><th>Qty</th><th>Amount</th></tr>${o.items.joinToString("") { "<tr><td>${esc(it.productName)}</td><td>${it.qty}</td><td>₱${money(it.qty * it.unitPrice)}</td></tr>" }}</table>
<p class="t">Total: ₱${money(o.totalAmount)}</p><hr style="margin:20px 0;border:1px dashed #CBD5E1">""" }}</div>""", true)

    /** Writes html to the exports folder and opens it in the system browser (print dialog opens automatically). */
    fun openHtml(name: String, html: String): File {
        val f = File(exportDir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        f.writeText(html)
        runCatching { Desktop.getDesktop().browse(f.toURI()) }
        return f
    }

    /** Lets the user pick where to save an HTML file (ticket download). Returns the saved file or null. */
    fun saveHtmlAs(suggested: String, html: String): File? {
        val ch = JFileChooser().apply {
            dialogTitle = "Save Ticket Pass"
            selectedFile = File(System.getProperty("user.home"), suggested)
            fileFilter = FileNameExtensionFilter("HTML document", "html")
        }
        return if (ch.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) ch.selectedFile.also { it.writeText(html) } else null
    }

    private fun capture(w: Window): BufferedImage {
        val p = w.locationOnScreen
        return Robot().createScreenCapture(Rectangle(p.x, p.y, w.width, w.height))
    }

    /** File > Print: captures the window and sends it to a printer (choose "Print to PDF" to save a PDF). */
    fun printWindow(w: Window): Boolean {
        val img = capture(w)
        val job = PrinterJob.getPrinterJob()
        job.setPrintable { g, pf: PageFormat, page ->
            if (page > 0) return@setPrintable Printable.NO_SUCH_PAGE
            val s = minOf(pf.imageableWidth / img.width, pf.imageableHeight / img.height)
            (g as java.awt.Graphics2D).translate(pf.imageableX, pf.imageableY)
            g.scale(s, s)
            g.drawImage(img, 0, 0, null)
            Printable.PAGE_EXISTS
        }
        if (!job.printDialog()) return false
        job.print(); return true
    }

    /** File > Save Snapshot: saves a PNG of the current window. */
    fun saveSnapshot(w: Window): File? {
        val img = capture(w)
        val ch = JFileChooser().apply {
            dialogTitle = "Save Window Snapshot"
            selectedFile = File(System.getProperty("user.home"), "AgriTourism-${System.currentTimeMillis()}.png")
            fileFilter = FileNameExtensionFilter("PNG image", "png")
        }
        if (ch.showSaveDialog(w) != JFileChooser.APPROVE_OPTION) return null
        return ch.selectedFile.also { ImageIO.write(img, "png", it) }
    }
}
