package com.aesoftwaresolutions.solid.deductions;

import com.aesoftwaresolutions.solid.common.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}")
class DeductionController {

    record CreateVehicle(@NotBlank @Size(max = 120) String name, @Size(max = 300) String description,
                         LocalDate inServiceDate) {
    }

    record CreateTrip(@NotNull UUID vehicleId, @NotNull LocalDate tripDate, @NotNull BigDecimal miles,
                      @NotNull @Pattern(regexp = "business|commuting|personal|charity|medical") String category,
                      @Size(max = 300) String purpose, @Size(max = 200) String startLocation,
                      @Size(max = 200) String endLocation) {
    }

    record SaveHomeOffice(@NotNull @Pattern(regexp = "simplified|actual") String method,
                          @NotNull @Min(1) @Max(100000) Integer totalHomeSquareFeet,
                          @NotNull @Min(1) @Max(100000) Integer officeSquareFeet,
                          @Min(1) @Max(12) Integer monthsUsed) {
    }

    private final DeductionService deductions;

    DeductionController(DeductionService deductions) {
        this.deductions = deductions;
    }

    @GetMapping("/vehicles")
    List<DeductionModels.Vehicle> vehicles(@PathVariable UUID orgId, @PathVariable UUID entityId) {
        return deductions.listVehicles(orgId, entityId);
    }

    @PostMapping("/vehicles")
    @ResponseStatus(HttpStatus.CREATED)
    DeductionModels.Vehicle createVehicle(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                          @Valid @RequestBody CreateVehicle body) {
        return deductions.createVehicle(orgId, entityId, body.name(), body.description(), body.inServiceDate());
    }

    @GetMapping("/mileage-trips")
    List<DeductionModels.Trip> trips(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                     @RequestParam(required = false) Integer taxYear,
                                     @RequestParam(required = false) String category) {
        return deductions.listTrips(orgId, entityId, taxYear, category);
    }

    @PostMapping("/mileage-trips")
    @ResponseStatus(HttpStatus.CREATED)
    DeductionModels.Trip addTrip(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                 @Valid @RequestBody CreateTrip body) {
        return deductions.addTrip(orgId, entityId, body.vehicleId(), body.tripDate(), body.miles(), body.category(),
                body.purpose(), body.startLocation(), body.endLocation());
    }

    @DeleteMapping("/mileage-trips/{tripId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteTrip(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID tripId) {
        deductions.deleteTrip(orgId, entityId, tripId);
    }

    @GetMapping("/reports/mileage")
    DeductionModels.MileageReport mileage(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                          @RequestParam int taxYear) {
        return deductions.mileageReport(orgId, entityId, taxYear);
    }

    @GetMapping("/home-office")
    DeductionModels.HomeOffice homeOffice(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                          @RequestParam int taxYear) {
        return deductions.getHomeOffice(orgId, entityId, taxYear)
                .orElseThrow(() -> new NotFoundException("No home office declared for " + taxYear));
    }

    @PutMapping("/home-office")
    DeductionModels.HomeOffice saveHomeOffice(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                              @RequestParam int taxYear, @Valid @RequestBody SaveHomeOffice body) {
        return deductions.saveHomeOffice(orgId, entityId, taxYear, body.method(), body.totalHomeSquareFeet(),
                body.officeSquareFeet(), body.monthsUsed());
    }

    @GetMapping("/reports/home-office")
    DeductionModels.HomeOfficeReport homeOfficeReport(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                                      @RequestParam int taxYear) {
        return deductions.homeOfficeReport(orgId, entityId, taxYear)
                .orElseThrow(() -> new NotFoundException("No home office declared for " + taxYear));
    }
}
