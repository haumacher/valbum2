# One e-mail address or phone number picked in the phone's own contacts picker
# (issue #201). Part of VAlbum2, not published.
Pod::Spec.new do |s|
  s.name             = 'contact_pick'
  s.version          = '1.0.0'
  s.summary          = 'Picks one e-mail address or phone number without the contacts permission.'
  s.homepage         = 'https://github.com/haumacher/valbum2'
  s.license          = { :type => 'AGPL-3.0' }
  s.author           = { 'VAlbum2' => 'https://github.com/haumacher/valbum2' }
  s.source           = { :path => '.' }
  s.source_files     = 'Classes/**/*'
  s.dependency 'Flutter'
  s.platform         = :ios, '11.0'
  s.frameworks       = 'Contacts', 'ContactsUI'
  s.swift_version    = '5.0'
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES' }
end
