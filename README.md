# Help

Program that refreshes your resume on <https://www.naukri.com/>, <https://www.monsterindia.com/> & <https://instahyre.com> 

## How to Build

1. Ensure you have atleast Java 17 installed
2. Run `./gradlew nativeCompile` which generates native executable under this path `build/native/nativeCompile/resume-refresher`
3. Run generated executable with following arguments
```sh
# without debugging requests & responses
eval build/native/nativeCompile/resume-refresher \
  --app.resume.path="<path to pdf/docx>" \
  --app.resume.filename="<resume.pdf/docx>" \
  --app.naukri.username="<naukri username>" \
  --app.naukri.password="<naukri password>" \ 
  --app.monster.username="<monster username>" \ 
  --app.monster.password="<monster password>" \ 
  --app.instahyre.username="<instahyre username>" \ 
  --app.instahyre.password="<instahyre username>"

# if you want debug requests & responses sent to respective APIs
eval build/native/nativeCompile/resume-refresher \
  --app.resume.path="<path to pdf/docx>" \
  --app.resume.filename="<resume.pdf/docx>" \
  --app.naukri.username="<naukri username>" \
  --app.naukri.password="<naukri password>" \ 
  --app.monster.username="<monster username>" \ 
  --app.monster.password="<monster password>" \ 
  --app.instahyre.username="<instahyre username>" \ 
  --app.instahyre.password="<instahyre username>" \ 
  --app.debug
```

> NOTE: You may need to run `gradlew.bat nativeCompile` in windows to generate the image & run generated `build/native/nativeCompile/resume-refresher` using command line without `eval`

## Schedule cron job in Ubuntu Desktop

1. Create following script `resume-refresh-with-desktop-notifications.sh`
```shell
#!/usr/bin/sh

# required for notify-send to work properly and send desktop notifications to current user
export XDG_RUNTIME_DIR=/run/user/$(id -u)

app_naukri_password='<naukri password>' \
app_monster_password='<monster password>' \
app_instahyre_password='<instahyre password>' \
/path/to/resume-refresher \
--app.resume.path="<path to pdf/docx>" \
--app.resume.filename="<resume.pdf/docx>" \
--app.naukri.username="<naukri username>" \
--app.monster.username="<monster username>" \
--app.instahyre.username="<instahyre username>" \
--app.debug \
>"<path to log>" 2>&1

grep "#block terminated with an error" "<path to log>"

# Desktop notifications in ubuntu
if [ $? != 0 ]; then
  /usr/bin/notify-send -t 0 "SUCCESS: Resume refresh" "<path to log>";
else
  /usr/bin/notify-send -u critical -t 0 "FAIL: Resume refresh" "<path to log>";
fi
```
2. Schedule cron job by running `crontab -e` & Add this
```shell
# run resume refresh program every 30mins
*/30 * * * * <path to resume-refresh-with-desktop-notifications.sh>
```
3. List existing cron jobs using `crontab -l`
