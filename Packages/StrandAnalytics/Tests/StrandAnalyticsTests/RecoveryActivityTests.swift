import XCTest
@testable import StrandAnalytics

/// Pins the recovery-activity classification. The same rows are the expected literal in the Android
/// RecoveryActivityTest (pasted from this implementation's stdout), so a change on either side that the
/// other does not make fails one of the two suites.
final class RecoveryActivityTests: XCTestCase {

    /// (input, key, isRecovery)
    private let table: [(String, String, Bool)] = [
        ("Stretching", "stretching", true),
        ("Meditation", "meditation", true),
        ("Breathwork", "breathwork", true),
        ("Sauna", "sauna", true),
        ("Hot bath", "hotbath", true),
        ("Ice bath", "icebath", true),
        ("Massage", "massage", true),
        ("IceBath", "icebath", true),
        ("ICE-BATH", "icebath", true),
        ("ice_bath", "icebath", true),
        (" ice  bath ", "icebath", true),
        ("HotTub", "hottub", true),
        ("Hot Tub", "hottub", true),
        ("Massage Therapy", "massagetherapy", true),
        ("PercussiveMassage", "percussivemassage", true),
        ("Cold Plunge", "coldplunge", true),
        ("Red Light Therapy", "redlighttherapy", true),
        ("Guided breathing", "guidedbreathing", true),
        ("Mindfulness", "mindfulness", true),
        ("泡澡", "泡澡", true),
        ("桑拿", "桑拿", true),
        ("冰浴", "冰浴", true),
        ("按摩", "按摩", true),
        ("拉伸", "拉伸", true),
        ("冥想", "冥想", true),
        ("呼吸练习", "呼吸练习", true),
        ("热水浴", "热水浴", true),
        ("泡　澡", "泡澡", true),
        ("Yoga", "yoga", false),
        ("Pilates", "pilates", false),
        ("Other", "other", false),
        ("Running", "running", false),
        ("Ice hockey", "icehockey", false),
        ("Bathtub", "bathtub", false),
        ("Saunas", "saunas", false),
        ("Stretch", "stretch", true),
        ("Bath", "bath", true),
        ("detected", "detected", false),
        ("Activity", "activity", false),
        ("", "", false),
        ("  ", "", false),
        ("Ünstretching", "Ünstretching", false),
        ("Massage🙂", "massage🙂", false),
        ("ŞAUNA", "Şauna", false),
        ("Cooldown", "cooldown", false),
        ("Mobility", "mobility", false),
    ]

    func testClassificationTable() {
        for (input, key, expected) in table {
            XCTAssertEqual(RecoveryActivity.key(input), key, "key(\"\(input)\")")
            XCTAssertEqual(RecoveryActivity.isRecovery(input), expected, "isRecovery(\"\(input)\")")
        }
    }

    func testEveryCatalogueNameIsARecoveryActivity() {
        XCTAssertEqual(RecoveryActivity.catalogueNames,
                       ["Stretching", "Meditation", "Breathwork", "Sauna", "Hot bath", "Ice bath", "Massage"])
        for name in RecoveryActivity.catalogueNames {
            XCTAssertTrue(RecoveryActivity.isRecovery(name), name)
        }
    }
}
