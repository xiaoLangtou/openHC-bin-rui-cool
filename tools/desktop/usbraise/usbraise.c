/*
 * usbraise —— macOS 版 USB 拉起工具（配合 openhc-desktop 使用）。
 *
 * 车机端（OpenHC app）在有线连接时干的第一件事：给普通状态的华为手机发一次 USB 控制传输
 * （bmRequestType 0x40、bRequest 0x34、wValue 0、wIndex 0xA0、91 字节 carInfo），
 * 手机收到后 1～2 秒断开重枚举成 CDC NCM USB 网卡。这里在 macOS 上用 libusb 做同样的事。
 *
 * 用法：
 *   clang -O2 -o usbraise usbraise.c -I/opt/homebrew/include -L/opt/homebrew/lib -lusb-1.0
 *   ./usbraise carinfo.bin              # 发 carInfo，等手机重枚举成网卡
 *   ./usbraise --list                   # 只列出 USB 设备（认手机的产品号）
 *
 * 手机状态（与 OpenHC 车机端 HuaweiPhone.kt 一致）：
 *   - 普通状态：产品号 0x107D / 0x107E / 0x107F（MTP、大容量存储等），发 carInfo；
 *   - 开着 USB 调试：产品号 0x5000，发 carInfo 手机不理——先在手机上关掉 USB 调试；
 *   - 已重枚举成网卡：多出 CDC NCM 控制口（类 2 / 子类 13）和数据口（类 10）。
 */

#include <libusb-1.0/libusb.h>
#include <arpa/inet.h>
#include <ifaddrs.h>
#include <net/if_dl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define CARINFO_SIZE 91
#define HUAWEI_VID 0x12d1

/* 普通状态的产品号（HuaweiPhone.kt 实测值）。 */
static const int NORMAL_PIDS[] = { 0x107D, 0x107E, 0x107F };

static int has_ncm_interface(libusb_device *dev) {
    struct libusb_config_descriptor *config;
    if (libusb_get_active_config_descriptor(dev, &config) != 0 &&
        libusb_get_config_descriptor(dev, 0, &config) != 0) {
        return 0;
    }
    int found = 0;
    for (int i = 0; i < config->bNumInterfaces && !found; i++) {
        for (int a = 0; a < config->interface[i].num_altsetting && !found; a++) {
            const struct libusb_interface_descriptor *d = &config->interface[i].altsetting[a];
            /* CDC NCM 控制口：类 2（通信）、子类 13（NCM）；数据口类 10（CDC 数据）。 */
            if ((d->bInterfaceClass == 2 && d->bInterfaceSubClass == 13) || d->bInterfaceClass == 10) {
                found = 1;
            }
        }
    }
    libusb_free_config_descriptor(config);
    return found;
}

static void get_strings(libusb_device *dev, char *out, size_t outSize) {
    out[0] = '\0';
    struct libusb_device_descriptor desc;
    if (libusb_get_device_descriptor(dev, &desc) != 0) return;
    if (desc.iProduct == 0 && desc.iManufacturer == 0) return;
    libusb_device_handle *handle;
    if (libusb_open(dev, &handle) != 0) return;
    char mfg[128] = "", prod[128] = "";
    if (desc.iManufacturer) libusb_get_string_descriptor_ascii(handle, desc.iManufacturer, (unsigned char *)mfg, sizeof(mfg) - 1);
    if (desc.iProduct) libusb_get_string_descriptor_ascii(handle, desc.iProduct, (unsigned char *)prod, sizeof(prod) - 1);
    libusb_close(handle);
    snprintf(out, outSize, "%s %s", mfg, prod);
}

static void list_devices(void) {
    libusb_context *ctx;
    if (libusb_init(&ctx) != 0) { fprintf(stderr, "libusb 初始化失败\n"); exit(1); }
    libusb_device **list;
    ssize_t count = libusb_get_device_list(ctx, &list);
    for (ssize_t i = 0; i < count; i++) {
        struct libusb_device_descriptor desc;
        if (libusb_get_device_descriptor(list[i], &desc) != 0) continue;
        char names[256];
        get_strings(list[i], names, sizeof(names));
        int ncm = has_ncm_interface(list[i]);
        printf("  %04x:%04x  %-32s %s\n", desc.idVendor, desc.idProduct, names,
               ncm ? "[含 CDC/NCM 网卡接口]" : "");
    }
    libusb_free_device_list(list, 1);
    libusb_exit(ctx);
}

/* 发送前的网络接口快照（数组由调用方 free()）。 */
static int snapshot_interfaces(char ***names) {
    *names = NULL;
    struct ifaddrs *addrs;
    if (getifaddrs(&addrs) != 0) return 0;
    int count = 0;
    for (struct ifaddrs *a = addrs; a; a = a->ifa_next) {
        if (a->ifa_addr && a->ifa_addr->sa_family == AF_LINK) count++;
    }
    *names = calloc((size_t)count + 1, sizeof(char *));
    int i = 0;
    for (struct ifaddrs *a = addrs; a; a = a->ifa_next) {
        if (a->ifa_addr && a->ifa_addr->sa_family == AF_LINK) (*names)[i++] = strdup(a->ifa_name);
    }
    freeifaddrs(addrs);
    return count;
}

/* 等待出现快照里没有的新接口（手机 NCM），找到立即返回接口名。 */
static char *wait_for_new_interface(char **before, int timeoutSeconds) {
    for (int elapsed = 0; elapsed < timeoutSeconds * 10; elapsed++) {
        struct ifaddrs *addrs;
        if (getifaddrs(&addrs) == 0) {
            for (struct ifaddrs *a = addrs; a; a = a->ifa_next) {
                if (!a->ifa_addr || a->ifa_addr->sa_family != AF_LINK) continue;
                int known = 0;
                for (char **b = before; *b; b++) {
                    if (strcmp(*b, a->ifa_name) == 0) { known = 1; break; }
                }
                if (!known) {
                    char *found = strdup(a->ifa_name);
                    freeifaddrs(addrs);
                    return found;
                }
            }
            freeifaddrs(addrs);
        }
        usleep(100 * 1000);
    }
    return NULL;
}

int main(int argc, char **argv) {
    const char *carInfoPath = NULL;
    const char *ipOption = NULL;
    int listOnly = 0;
    int force = 0;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--list") == 0) listOnly = 1;
        else if (strcmp(argv[i], "--force") == 0) force = 1;
        else if (strcmp(argv[i], "--ip") == 0 && i + 1 < argc) ipOption = argv[++i];
        else carInfoPath = argv[i];
    }
    if (listOnly) {
        printf("USB 设备：\n");
        list_devices();
        return 0;
    }
    if (carInfoPath == NULL) {
        fprintf(stderr, "用法：sudo usbraise --ip <IP> <carInfo文件>   （发 carInfo，新接口出现立即配 IP）\n"
                        "      usbraise --list                （列出 USB 设备）\n"
                        "      usbraise --force …             （非普通状态产品号也照发，实验用）\n");
        return 1;
    }

    FILE *f = fopen(carInfoPath, "rb");
    if (!f) { perror(carInfoPath); return 1; }
    unsigned char carInfo[CARINFO_SIZE];
    size_t got = fread(carInfo, 1, sizeof(carInfo), f);
    fclose(f);
    if (got != CARINFO_SIZE) {
        fprintf(stderr, "carInfo 应为 %d 字节，读到 %zu 字节\n", CARINFO_SIZE, got);
        return 1;
    }
    if (memcmp(carInfo, "hwnearby", 8) != 0) {
        fprintf(stderr, "carInfo 头不对（不是 hwnearby），文件是不是写错了\n");
        return 1;
    }

    libusb_context *ctx;
    if (libusb_init(&ctx) != 0) { fprintf(stderr, "libusb 初始化失败\n"); return 1; }

    /* 先看手机是不是插着、状态对不对。 */
    libusb_device *target = NULL;
    libusb_device **list;
    ssize_t count = libusb_get_device_list(ctx, &list);
    for (ssize_t i = 0; i < count; i++) {
        struct libusb_device_descriptor desc;
        if (libusb_get_device_descriptor(list[i], &desc) != 0) continue;
        if (desc.idVendor != HUAWEI_VID) continue;
        char names[256];
        get_strings(list[i], names, sizeof(names));
        if (has_ncm_interface(list[i])) {
            printf("手机已经重枚举成 USB 网卡（%s）。直接配 IP，不用再发 carInfo。\n", names);
            libusb_free_device_list(list, 1);
            libusb_exit(ctx);
            return 0;
        }
        int normal = 0;
        for (size_t k = 0; k < sizeof(NORMAL_PIDS) / sizeof(NORMAL_PIDS[0]); k++) {
            if (desc.idProduct == NORMAL_PIDS[k]) normal = 1;
        }
        printf("华为设备 %04x:%04x（%s）：%s\n", desc.idVendor, desc.idProduct,
               names, normal ? "普通状态，可以发 carInfo" : "非普通状态产品号（开 USB 调试时发 carInfo 手机不理）");
        if ((normal || force) && !target) { target = list[i]; }
    }
    if (!target) {
        fprintf(stderr, "没有找到可发的华为手机。用 --list 看看；开着 USB 调试的话先关掉，\n"
                        "插上后在通知栏把 USB 连接方式选成「传输文件」。\n");
        libusb_free_device_list(list, 1);
        libusb_exit(ctx);
        return 1;
    }

    /* 发 carInfo：厂商请求发到设备本身，不需要占用接口。 */
    libusb_device_handle *handle;
    int rc = libusb_open(target, &handle);
    if (rc != 0) {
        fprintf(stderr, "打不开 USB 设备：%s\n", libusb_error_name(rc));
        libusb_free_device_list(list, 1);
        libusb_exit(ctx);
        return 1;
    }
    libusb_free_device_list(list, 1);
    /* 发送前快照接口；发完等新接口（手机 NCM），出现立即配 IP——手机收到 ARP 后几毫秒就连 TCP，
     * NCM 态约 19 秒没人连就切回（WiredSession.kt 实测值），所以必须一气呵成。 */
    char **before = NULL;
    int beforeCount = snapshot_interfaces(&before);
    (void)beforeCount;
    int sent = libusb_control_transfer(handle, 0x40, 0x34, 0, 0xA0, carInfo, CARINFO_SIZE, 5000);
    libusb_close(handle);
    if (sent != CARINFO_SIZE) {
        fprintf(stderr, "carInfo 发送失败：发了 %d 字节\n", sent);
        libusb_exit(ctx);
        return 1;
    }
    printf("carInfo（%d 字节）已发出，等手机重枚举成 USB 网卡…\n", sent);

    char *iface = wait_for_new_interface(before, 25);
    if (iface) {
        printf("✓ 手机已枚举成 USB 网卡，新接口：%s\n", iface);
        if (ipOption) {
            execl("/sbin/ifconfig", "ifconfig", iface, "inet", ipOption, "netmask", "255.255.255.0", "up", (char *)NULL);
            fprintf(stderr, "ifconfig 执行失败（需要 sudo 跑本工具）\n");
        } else {
            printf("下一步立刻配 IP（19 秒窗口）：sudo ifconfig %s inet <IP> netmask 255.255.255.0 up\n", iface);
        }
    } else {
        fprintf(stderr, "25 秒内没等到手机枚举成网卡。手机屏幕上可能有弹窗；或再跑一次 --list 看状态。\n");
        libusb_exit(ctx);
        return 2;
    }
    libusb_exit(ctx);
    return 0;
}
