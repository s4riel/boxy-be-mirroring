package com.boxy.boxy.modules.dashboard.service;

import com.boxy.boxy.core.security.SecurityUtils;
import com.boxy.boxy.modules.administration.entity.Branch;
import com.boxy.boxy.modules.administration.entity.Warehouse;
import com.boxy.boxy.modules.administration.repository.BranchRepository;
import com.boxy.boxy.modules.administration.repository.UserRepository;
import com.boxy.boxy.modules.administration.repository.WarehouseRepository;
import com.boxy.boxy.modules.catalog.entity.Product;
import com.boxy.boxy.modules.catalog.repository.ProductRepository;
import com.boxy.boxy.modules.inventory.entity.StockLevel;
import com.boxy.boxy.modules.inventory.repository.StockLevelRepository;
import com.boxy.boxy.modules.inventory.repository.StockMovementRepository;
import com.boxy.boxy.modules.purchasing.entity.PurchaseOrder;
import com.boxy.boxy.modules.purchasing.entity.Supplier;
import com.boxy.boxy.modules.purchasing.repository.PurchaseOrderRepository;
import com.boxy.boxy.modules.purchasing.repository.SupplierRepository;
import com.boxy.boxy.modules.sales.entity.Customer;
import com.boxy.boxy.modules.sales.entity.Invoice;
import com.boxy.boxy.modules.sales.entity.InvoiceItem;
import com.boxy.boxy.modules.sales.entity.SalesOrder;
import com.boxy.boxy.modules.sales.repository.CustomerRepository;
import com.boxy.boxy.modules.sales.repository.InvoiceRepository;
import com.boxy.boxy.modules.sales.repository.SalesOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ReportService {

    private final InvoiceRepository invoiceRepository;
    private final ProductRepository productRepository;
    private final StockLevelRepository stockLevelRepository;
    private final StockMovementRepository stockMovementRepository;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final BranchRepository branchRepository;
    private final WarehouseRepository warehouseRepository;
    private final UserRepository userRepository;
    private final com.boxy.boxy.modules.administration.repository.CompanyRepository companyRepository;

    /** The signed-in user's company name — the header of an exported report. */
    @Transactional(readOnly = true)
    public String currentCompanyName() {
        return companyRepository.findById(SecurityUtils.requireCurrentCompanyId())
                .map(com.boxy.boxy.modules.administration.entity.Company::getName)
                .orElse("");
    }

    // --- FINANCIAL REPORTS ---

    // --- FILTERS ---

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getReportBranches() {
        Long companyId = SecurityUtils.requireCurrentCompanyId();
        List<Branch> branches = branchRepository.findByCompanyIdAndDeletedAtIsNull(companyId);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Branch b : branches) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", String.valueOf(b.getId()));
            map.put("name", b.getName());
            map.put("code", b.getCode());
            map.put("isMain", Boolean.TRUE.equals(b.getIsMain()));
            list.add(map);
        }
        return list;
    }
}
