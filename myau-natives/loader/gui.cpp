#include <windows.h>
#include <windowsx.h>
#include <gdiplus.h>
#include <objidl.h>
#include <dwmapi.h>

#include <string>

#include "inject.h"
#include "resource.h"

namespace {

constexpr UINT WM_LOG_LINE = WM_APP + 1;
constexpr UINT WM_INJECT_DONE = WM_APP + 2;
constexpr UINT_PTR TIMER_RESET = 1;
constexpr UINT_PTR TIMER_TARGET = 2;
constexpr UINT RESET_DELAY_MS = 5000;

constexpr int WINDOW_WIDTH = 720;
constexpr int WINDOW_HEIGHT = 340;

constexpr int LOGO_TOP = 106;
constexpr int LOGO_HEIGHT = 44;
constexpr int ACTION_TOP = 186;
constexpr int ACTION_HEIGHT = 44;
constexpr int BUTTON_WIDTH = 420;
constexpr int STATUS_TOP = 254;
constexpr int STATUS_HEIGHT = 24;
constexpr int MARGIN = 22;
constexpr UINT TARGET_POLL_MS = 1000;

constexpr int CLOSE_WIDTH = 46;
constexpr int CLOSE_HEIGHT = 32;
constexpr int DRAG_HEIGHT = 40;

const COLORREF COLOUR_BACKGROUND = RGB(10, 10, 12);
const COLORREF COLOUR_CLOSE_HOT = RGB(196, 43, 47);

enum class Stage {
    IDLE,
    WORKING,
    DONE_OK,
    DONE_FAILED
};

HWND g_window = nullptr;
HWND g_button = nullptr;
HBRUSH g_backgroundBrush = nullptr;
HFONT g_buttonFont = nullptr;
Gdiplus::Bitmap *g_logo = nullptr;
Gdiplus::Rect g_logoContent;
ULONG_PTR g_gdiplusToken = 0;
Stage g_stage = Stage::IDLE;
bool g_buttonHot = false;
bool g_closeHot = false;
bool g_badgeVisible = false;
std::wstring g_status;
DWORD g_targetPid = 0;
std::wstring g_targetLauncher;

struct Payload {
    const void *bytes = nullptr;
    DWORD size = 0;
};

Payload payload(int id) {
    Payload out;
    HRSRC found = FindResourceW(nullptr, MAKEINTRESOURCEW(id), MAKEINTRESOURCEW(10));
    if (!found) {
        return out;
    }
    HGLOBAL loaded = LoadResource(nullptr, found);
    if (!loaded) {
        return out;
    }
    out.bytes = LockResource(loaded);
    out.size = SizeofResource(nullptr, found);
    return out;
}

Gdiplus::Bitmap *loadLogo() {
    Payload png = payload(IDR_LOGO);
    if (!png.bytes || png.size == 0) {
        return nullptr;
    }
    HGLOBAL buffer = GlobalAlloc(GMEM_MOVEABLE, png.size);
    if (!buffer) {
        return nullptr;
    }
    void *target = GlobalLock(buffer);
    memcpy(target, png.bytes, png.size);
    GlobalUnlock(buffer);
    IStream *stream = nullptr;
    if (CreateStreamOnHGlobal(buffer, TRUE, &stream) != S_OK) {
        GlobalFree(buffer);
        return nullptr;
    }
    Gdiplus::Bitmap *image = Gdiplus::Bitmap::FromStream(stream);
    stream->Release();
    if (image && image->GetLastStatus() != Gdiplus::Ok) {
        delete image;
        return nullptr;
    }
    return image;
}
Gdiplus::Rect trimToContent(Gdiplus::Bitmap *image) {
    Gdiplus::Rect whole(0, 0, (INT)image->GetWidth(), (INT)image->GetHeight());
    Gdiplus::BitmapData data;
    if (image->LockBits(&whole, Gdiplus::ImageLockModeRead, PixelFormat32bppARGB, &data)
            != Gdiplus::Ok) {
        return whole;
    }
    int left = whole.Width;
    int top = whole.Height;
    int right = -1;
    int bottom = -1;
    for (int y = 0; y < whole.Height; y++) {
        const BYTE *row = (const BYTE *)data.Scan0 + (INT_PTR)y * data.Stride;
        for (int x = 0; x < whole.Width; x++) {
            if (row[x * 4 + 3] <= 16) {
                continue;
            }
            if (x < left) left = x;
            if (x > right) right = x;
            if (y < top) top = y;
            if (y > bottom) bottom = y;
        }
    }
    image->UnlockBits(&data);
    if (right < left || bottom < top) {
        return whole;
    }
    return Gdiplus::Rect(left, top, right - left + 1, bottom - top + 1);
}
RECT closeRect(int clientWidth) {
    RECT box;
    box.right = clientWidth;
    box.left = clientWidth - CLOSE_WIDTH;
    box.top = 0;
    box.bottom = CLOSE_HEIGHT;
    return box;
}
RECT statusRect(int clientWidth) {
    RECT box = {0, STATUS_TOP, clientWidth, STATUS_TOP + STATUS_HEIGHT};
    return box;
}

void paintLogo(Gdiplus::Graphics &graphics, int clientWidth) {
    if (!g_logo) {
        Gdiplus::FontFamily family(L"Segoe UI");
        Gdiplus::Font font(&family, 34, Gdiplus::FontStyleBold, Gdiplus::UnitPixel);
        Gdiplus::SolidBrush brush(Gdiplus::Color(255, 235, 238, 245));
        Gdiplus::StringFormat centred;
        centred.SetAlignment(Gdiplus::StringAlignmentCenter);
        centred.SetLineAlignment(Gdiplus::StringAlignmentCenter);
        graphics.DrawString(L"Myau", -1, &font,
                            Gdiplus::RectF(0, (Gdiplus::REAL)LOGO_TOP,
                                           (Gdiplus::REAL)clientWidth,
                                           (Gdiplus::REAL)LOGO_HEIGHT),
                            &centred, &brush);
        return;
    }

    int available = (int)(clientWidth / 4.0);
    double scale = min((double)available / g_logoContent.Width,
                       (double)LOGO_HEIGHT / g_logoContent.Height);
    int width = (int)(g_logoContent.Width * scale);
    int height = (int)(g_logoContent.Height * scale);
    Gdiplus::Rect destination((clientWidth - width) / 2,
                              LOGO_TOP + (LOGO_HEIGHT - height) / 2, width, height);
    graphics.SetInterpolationMode(Gdiplus::InterpolationModeNearestNeighbor);
    graphics.SetPixelOffsetMode(Gdiplus::PixelOffsetModeHalf);
    graphics.DrawImage(g_logo, destination,
                       g_logoContent.X, g_logoContent.Y,
                       g_logoContent.Width, g_logoContent.Height,
                       Gdiplus::UnitPixel);
}
void paintClose(Gdiplus::Graphics &graphics, int clientWidth) {
    RECT box = closeRect(clientWidth);
    if (g_closeHot) {
        Gdiplus::SolidBrush hot(Gdiplus::Color(255, GetRValue(COLOUR_CLOSE_HOT),
                                               GetGValue(COLOUR_CLOSE_HOT),
                                               GetBValue(COLOUR_CLOSE_HOT)));
        graphics.FillRectangle(&hot, box.left, box.top,
                               box.right - box.left, box.bottom - box.top);
    }
    graphics.SetSmoothingMode(Gdiplus::SmoothingModeAntiAlias);
    Gdiplus::Pen pen(Gdiplus::Color(255, 224, 226, 232), 1.3f);
    float centreX = (box.left + box.right) / 2.0f;
    float centreY = (box.top + box.bottom) / 2.0f;
    const float arm = 5.0f;
    graphics.DrawLine(&pen, centreX - arm, centreY - arm, centreX + arm, centreY + arm);
    graphics.DrawLine(&pen, centreX + arm, centreY - arm, centreX - arm, centreY + arm);
    graphics.SetSmoothingMode(Gdiplus::SmoothingModeNone);
}
void paintBadge(Gdiplus::Graphics &graphics) {
    if (!g_badgeVisible) {
        return;
    }
    const Gdiplus::REAL x = (Gdiplus::REAL)MARGIN;
    const Gdiplus::REAL y = 18.0f;
    const Gdiplus::REAL width = 104.0f;
    const Gdiplus::REAL height = 26.0f;
    Gdiplus::SolidBrush fill(Gdiplus::Color(255, 46, 160, 67));
    graphics.FillRectangle(&fill, x, y, width, height);
    Gdiplus::FontFamily family(L"Segoe UI");
    Gdiplus::Font font(&family, 14, Gdiplus::FontStyleBold, Gdiplus::UnitPixel);
    Gdiplus::SolidBrush text(Gdiplus::Color(255, 255, 255, 255));
    Gdiplus::StringFormat centred;
    centred.SetAlignment(Gdiplus::StringAlignmentCenter);
    centred.SetLineAlignment(Gdiplus::StringAlignmentCenter);
    graphics.DrawString(L"Loaded", -1, &font,
                        Gdiplus::RectF(x, y, width, height), &centred, &text);
}
void paintStatus(Gdiplus::Graphics &graphics, int clientWidth) {
    if (g_status.empty()) {
        return;
    }
    Gdiplus::FontFamily family(L"Segoe UI");
    Gdiplus::Font font(&family, 13, Gdiplus::FontStyleRegular, Gdiplus::UnitPixel);
    Gdiplus::SolidBrush brush(Gdiplus::Color(255, 132, 136, 145));
    Gdiplus::StringFormat centred;
    centred.SetAlignment(Gdiplus::StringAlignmentCenter);
    centred.SetLineAlignment(Gdiplus::StringAlignmentCenter);
    centred.SetTrimming(Gdiplus::StringTrimmingEllipsisCharacter);
    centred.SetFormatFlags(Gdiplus::StringFormatFlagsNoWrap);
    graphics.DrawString(g_status.c_str(), -1, &font,
                        Gdiplus::RectF((Gdiplus::REAL)MARGIN, (Gdiplus::REAL)STATUS_TOP,
                                       (Gdiplus::REAL)(clientWidth - 2 * MARGIN),
                                       (Gdiplus::REAL)STATUS_HEIGHT),
                        &centred, &brush);
}

void paintFooter(Gdiplus::Graphics &graphics, int clientHeight) {
    Gdiplus::FontFamily family(L"Segoe UI");
    Gdiplus::Font font(&family, 12, Gdiplus::FontStyleRegular, Gdiplus::UnitPixel);
    Gdiplus::SolidBrush brush(Gdiplus::Color(255, 96, 99, 106));
    graphics.DrawString(L"Myau Injector Beta", -1, &font,
                        Gdiplus::PointF((Gdiplus::REAL)MARGIN,
                                        (Gdiplus::REAL)(clientHeight - 26)),
                        &brush);
}
void paintButton(const DRAWITEMSTRUCT *item) {
    FillRect(item->hDC, &item->rcItem, g_backgroundBrush);
    wchar_t text[128];
    GetWindowTextW(item->hwndItem, text, 128);
    bool down = (item->itemState & ODS_SELECTED) != 0;
    bool enabled = IsWindowEnabled(item->hwndItem) != FALSE;
    Gdiplus::Graphics graphics(item->hDC);
    graphics.SetSmoothingMode(Gdiplus::SmoothingModeAntiAlias);
    Gdiplus::Rect box(item->rcItem.left, item->rcItem.top,
                      item->rcItem.right - item->rcItem.left,
                      item->rcItem.bottom - item->rcItem.top);
    const int radius = (box.Height - 1) / 2;
    Gdiplus::GraphicsPath path;
    path.AddArc(box.X, box.Y, radius * 2, radius * 2, 180, 90);
    path.AddArc(box.GetRight() - radius * 2 - 1, box.Y, radius * 2, radius * 2, 270, 90);
    path.AddArc(box.GetRight() - radius * 2 - 1, box.GetBottom() - radius * 2 - 1,
                radius * 2, radius * 2, 0, 90);
    path.AddArc(box.X, box.GetBottom() - radius * 2 - 1, radius * 2, radius * 2, 90, 90);
    path.CloseFigure();
    BYTE grey = !enabled ? 38 : (down ? 44 : (g_buttonHot ? 74 : 58));
    Gdiplus::SolidBrush fill(Gdiplus::Color(255, grey, grey + 2, grey + 6));
    graphics.FillPath(&fill, &path);
    Gdiplus::FontFamily family(L"Segoe UI");
    Gdiplus::Font font(&family, 15, Gdiplus::FontStyleRegular, Gdiplus::UnitPixel);
    Gdiplus::SolidBrush ink(enabled ? Gdiplus::Color(255, 255, 255, 255)
                                    : Gdiplus::Color(255, 150, 153, 160));
    Gdiplus::StringFormat centred;
    centred.SetAlignment(Gdiplus::StringAlignmentCenter);
    centred.SetLineAlignment(Gdiplus::StringAlignmentCenter);
    centred.SetTrimming(Gdiplus::StringTrimmingEllipsisCharacter);
    centred.SetFormatFlags(Gdiplus::StringFormatFlagsNoWrap);
    graphics.DrawString(text, -1, &font,
                        Gdiplus::RectF((Gdiplus::REAL)box.X, (Gdiplus::REAL)box.Y,
                                       (Gdiplus::REAL)box.Width, (Gdiplus::REAL)box.Height),
                        &centred, &ink);
}

void refreshTarget() {
    if (g_stage == Stage::WORKING || g_stage == Stage::DONE_OK) {
        return;
    }
    DWORD pid = 0;
    std::wstring launcher;
    if (!findTarget(pid, launcher)) {
        if (g_targetPid != 0) {
            g_targetPid = 0;
            g_targetLauncher.clear();
        }
        SetWindowTextW(g_button, L"No game found");
        EnableWindow(g_button, FALSE);
        InvalidateRect(g_button, nullptr, TRUE);
        return;
    }
    if (pid == g_targetPid && launcher == g_targetLauncher) {
        return;
    }
    g_targetPid = pid;
    g_targetLauncher = launcher;
    wchar_t label[128];
    _snwprintf_s(label, _TRUNCATE, L"Inject into PID %lu - %s", pid, launcher.c_str());
    SetWindowTextW(g_button, label);
    EnableWindow(g_button, TRUE);
    InvalidateRect(g_button, nullptr, TRUE);
}
void setStatus(const std::wstring &text) {
    g_status = text;
    RECT box = statusRect(WINDOW_WIDTH);
    InvalidateRect(g_window, &box, TRUE);
}
void postLine(const std::wstring &line) {
    PostMessageW(g_window, WM_LOG_LINE, 0, (LPARAM) new std::wstring(line));
}
DWORD WINAPI injectThread(LPVOID) {
    Payload dll = payload(IDR_NATIVE_DLL);
    Payload jar = payload(IDR_CLIENT_JAR);
    if (!dll.bytes || !jar.bytes) {
        postLine(L"this executable was built without its payload -- nothing to inject");
        PostMessageW(g_window, WM_INJECT_DONE, FALSE, 0);
        return 0;
    }
    bool ok = runInjection(dll.bytes, dll.size, jar.bytes, jar.size, g_targetPid, postLine);
    PostMessageW(g_window, WM_INJECT_DONE, ok ? TRUE : FALSE, 0);
    return 0;
}
void enterStage(Stage stage) {
    g_stage = stage;
    switch (stage) {
        case Stage::WORKING:
            SetWindowTextW(g_button, L"Injecting...");
            EnableWindow(g_button, FALSE);
            break;
        case Stage::DONE_OK:
            SetWindowTextW(g_button, L"Loaded");
            EnableWindow(g_button, FALSE);
            g_badgeVisible = true;
            SetTimer(g_window, TIMER_RESET, RESET_DELAY_MS, nullptr);
            break;
        default:
            g_badgeVisible = false;

            g_targetPid = 0;
            refreshTarget();
            break;
    }
    InvalidateRect(g_window, nullptr, TRUE);
}
LRESULT CALLBACK windowProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam) {
    switch (message) {
        case WM_CREATE: {
            g_button = CreateWindowExW(0, L"BUTTON", L"Load",
                                       WS_CHILD | WS_VISIBLE | BS_OWNERDRAW,
                                       (WINDOW_WIDTH - BUTTON_WIDTH) / 2, ACTION_TOP,
                                       BUTTON_WIDTH, ACTION_HEIGHT, window,
                                       (HMENU)IDC_LOAD, nullptr, nullptr);
            g_status = L"Start the game, then press the button.";
            refreshTarget();
            SetTimer(window, TIMER_TARGET, TARGET_POLL_MS, nullptr);
            return 0;
        }
        case WM_ERASEBKGND: {
            RECT client;
            GetClientRect(window, &client);
            FillRect((HDC)wParam, &client, g_backgroundBrush);
            return 1;
        }
        case WM_PAINT: {
            PAINTSTRUCT paint;
            HDC dc = BeginPaint(window, &paint);
            RECT client;
            GetClientRect(window, &client);
            Gdiplus::Graphics graphics(dc);
            paintLogo(graphics, client.right);
            paintClose(graphics, client.right);
            paintBadge(graphics);
            paintStatus(graphics, client.right);
            paintFooter(graphics, client.bottom);
            EndPaint(window, &paint);
            return 0;
        }
        case WM_NCHITTEST: {
            POINT cursor = {GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam)};
            ScreenToClient(window, &cursor);
            RECT client;
            GetClientRect(window, &client);
            RECT close = closeRect(client.right);
            if (PtInRect(&close, cursor)) {
                return HTCLIENT;
            }
            return cursor.y < DRAG_HEIGHT ? HTCAPTION : HTCLIENT;
        }
        case WM_TIMER: {
            if (wParam == TIMER_RESET) {
                KillTimer(window, TIMER_RESET);
                enterStage(Stage::IDLE);
                setStatus(L"Start the game, then press the button.");
            } else if (wParam == TIMER_TARGET) {
                refreshTarget();
            }
            return 0;
        }
        case WM_DRAWITEM: {
            const DRAWITEMSTRUCT *item = (const DRAWITEMSTRUCT *)lParam;
            if (item->CtlID == IDC_LOAD) {
                paintButton(item);
                return TRUE;
            }
            break;
        }
        case WM_MOUSEMOVE: {
            POINT cursor = {GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam)};
            RECT client;
            GetClientRect(window, &client);
            RECT button;
            GetWindowRect(g_button, &button);
            MapWindowPoints(nullptr, window, (POINT *)&button, 2);
            bool buttonHot = IsWindowVisible(g_button) && PtInRect(&button, cursor);
            RECT close = closeRect(client.right);
            bool closeHot = PtInRect(&close, cursor) != FALSE;
            if (buttonHot != g_buttonHot) {
                g_buttonHot = buttonHot;
                InvalidateRect(g_button, nullptr, TRUE);
            }
            if (closeHot != g_closeHot) {
                g_closeHot = closeHot;
                InvalidateRect(window, &close, TRUE);
            }
            TRACKMOUSEEVENT track = {sizeof(track), TME_LEAVE, window, 0};
            TrackMouseEvent(&track);
            return 0;
        }
        case WM_MOUSELEAVE: {
            if (g_buttonHot) {
                g_buttonHot = false;
                InvalidateRect(g_button, nullptr, TRUE);
            }
            if (g_closeHot) {
                g_closeHot = false;
                InvalidateRect(window, nullptr, TRUE);
            }
            return 0;
        }
        case WM_LBUTTONDOWN: {
            POINT cursor = {GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam)};
            RECT client;
            GetClientRect(window, &client);
            RECT close = closeRect(client.right);
            if (PtInRect(&close, cursor)) {
                PostMessageW(window, WM_CLOSE, 0, 0);
            }
            return 0;
        }
        case WM_COMMAND: {
            if (LOWORD(wParam) == IDC_LOAD && g_stage != Stage::WORKING) {
                setStatus(L"looking for the game...");
                enterStage(Stage::WORKING);
                CloseHandle(CreateThread(nullptr, 0, injectThread, nullptr, 0, nullptr));
            }
            return 0;
        }
        case WM_LOG_LINE: {
            std::wstring *line = (std::wstring *)lParam;
            setStatus(*line);
            delete line;
            return 0;
        }
        case WM_INJECT_DONE: {
            enterStage(wParam ? Stage::DONE_OK : Stage::DONE_FAILED);
            return 0;
        }
        case WM_DESTROY: {
            g_window = nullptr;
            PostQuitMessage(0);
            return 0;
        }
        default:
            break;
    }
    return DefWindowProcW(window, message, wParam, lParam);
}
}
int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int show) {
    Gdiplus::GdiplusStartupInput startup;
    Gdiplus::GdiplusStartup(&g_gdiplusToken, &startup, nullptr);
    g_logo = loadLogo();
    if (g_logo) {
        g_logoContent = trimToContent(g_logo);
    }
    g_backgroundBrush = CreateSolidBrush(COLOUR_BACKGROUND);
    g_buttonFont = CreateFontW(40, 0, 0, 0, FW_SEMIBOLD, FALSE, FALSE, FALSE, DEFAULT_CHARSET,
                               OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
                               DEFAULT_PITCH | FF_SWISS, L"Segoe UI");
    HICON icon = LoadIconW(instance, MAKEINTRESOURCEW(IDI_APP));
    WNDCLASSEXW cls = {sizeof(cls)};
    cls.lpfnWndProc = windowProc;
    cls.hInstance = instance;
    cls.hCursor = LoadCursor(nullptr, IDC_ARROW);
    cls.hbrBackground = g_backgroundBrush;
    cls.lpszClassName = L"MyauLoader";
    cls.hIcon = icon;
    cls.hIconSm = icon;
    RegisterClassExW(&cls);
    RECT wanted = {0, 0, WINDOW_WIDTH, WINDOW_HEIGHT};
    AdjustWindowRect(&wanted, WS_POPUP, FALSE);
    g_window = CreateWindowExW(WS_EX_APPWINDOW, cls.lpszClassName, L"Myau", WS_POPUP,
                               CW_USEDEFAULT, CW_USEDEFAULT,
                               wanted.right - wanted.left, wanted.bottom - wanted.top,
                               nullptr, nullptr, instance, nullptr);
    if (!g_window) {
        return 1;
    }
    RECT work;
    SystemParametersInfoW(SPI_GETWORKAREA, 0, &work, 0);
    SetWindowPos(g_window, nullptr,
                 work.left + (work.right - work.left - WINDOW_WIDTH) / 2,
                 work.top + (work.bottom - work.top - WINDOW_HEIGHT) / 2,
                 WINDOW_WIDTH, WINDOW_HEIGHT, SWP_NOZORDER);
    BOOL dark = TRUE;
    if (FAILED(DwmSetWindowAttribute(g_window, 20, &dark, sizeof(dark)))) {
        DwmSetWindowAttribute(g_window, 19, &dark, sizeof(dark));
    }
    ShowWindow(g_window, show);
    UpdateWindow(g_window);
    MSG message;
    while (GetMessageW(&message, nullptr, 0, 0) > 0) {
        if (!IsDialogMessageW(g_window, &message)) {
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }
    }
    delete g_logo;
    Gdiplus::GdiplusShutdown(g_gdiplusToken);
    return 0;
}
